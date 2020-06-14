/**
 * Copyright (c) 2019 Uber Technologies, Inc.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.piranha;

import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;

import com.google.auto.service.AutoService;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.util.FindIdentifiers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** @author murali@uber.com (Murali Krishna Ramanathan) */
@AutoService(BugChecker.class)
@BugPattern(
    name = "Piranha",
    altNames = {"XPFlagCleaner"},
    summary = "Cleans stale XP flags.",
    severity = SUGGESTION)
public class XPFlagCleaner extends BugChecker
    implements BugChecker.AssignmentTreeMatcher,
        BugChecker.BinaryTreeMatcher,
        BugChecker.CompilationUnitTreeMatcher,
        BugChecker.ConditionalExpressionTreeMatcher,
        BugChecker.ClassTreeMatcher,
        BugChecker.ExpressionStatementTreeMatcher,
        BugChecker.IfTreeMatcher,
        BugChecker.ImportTreeMatcher,
        BugChecker.MethodInvocationTreeMatcher,
        BugChecker.ReturnTreeMatcher,
        BugChecker.UnaryTreeMatcher,
        BugChecker.VariableTreeMatcher,
        BugChecker.MethodTreeMatcher {

  /**
   * Do not try to auto-delete imports with these common/generic names, as multiple treament groups
   * for different flags are likely to re-use these names.
   */
  private static final ImmutableSet<String> COMMON_GROUP_NAMES =
      ImmutableSet.of("control", "enabled", "disabled", "treatment", "treated");

  private static final int DONTCARE = -1;
  private static final int ZERO = 0;
  private static final String TRUE = "true";
  private static final String FALSE = "false";
  private static final String EMPTY = "";

  // Allowed fields for a method property in the config file.
  // Entered under the top-level "methodProperties" in properties.json.
  // By default, the flagType, methodName and argumentIndex fields are mandatory.
  // The returnType and receiverType fields are optional.
  private static final String FLAG_TYPE_KEY = "flagType";
  private static final String METHOD_NAME_KEY = "methodName";
  private static final String ARGUMENT_INDEX_KEY = "argumentIndex";
  private static final String RETURN_TYPE_STRING = "returnType";
  private static final String RECEIVER_TYPE_STRING = "receiverType";

  /**
   * Holds the mapping of flagType string to API. Eg: "treated" -> API.IS_TREATED. Is initialized
   * once and then accessed without updating.
   */
  private static final Map<String, API> flagTypeToAPIMap = initializeFlagTypeToAPIMap();

  /**
   * Used to defer initialization until after traversal has begun, since Error Prone eats all error
   * messages thrown inside the constructor.
   */
  private boolean initialized = false;

  private boolean disabled = false;
  private ErrorProneFlags flags = null;

  private String xpFlagName = "_xpflag_dummy";

  /** Enumerator for different values returned by expression evaluation */
  private enum Value {
    TRUE,
    FALSE,
    BOT
  }

  /** Identify the appropriate API for experiments */
  private enum API {
    IS_TREATED,
    IS_CONTROL,
    IS_TREATMENT_GROUP_CHECK,
    DELETE_METHOD,
    UNKNOWN
  }

  private Symbol xpSym = null;
  private boolean isTreated = true;
  private String treatmentGroup = "";
  private String treatmentGroupsEnum = null; // FQN of the enum containing the treatment group names

  /**
   * when source is refactored, this specifies the end position for the refactoring until the
   * processing of the source reaches this position. otherwise, the value is -1.
   */
  private int endPos = DONTCARE;

  /**
   * Information provided in the properties.json config file.
   *
   * <p>configMethodsMap is a map where key is method name and value is a list where each item in
   * the list is a map that corresponds to each method property from properties.json. In most cases,
   * the list would have only one element. But if someone reuses the same method name with different
   * returnType/receiverType/argumentIndex, the list would have each method property map as one
   * element.
   */
  private final Map<String, List<Map<String, Object>>> configMethodProperties = new HashMap<>();

  private final HashSet<String> handledAnnotations = new HashSet<>();
  private String linkURL;

  /** State used to track usage counts and delete corresponding declarations if needed. */
  private TreePath cuPath = null;

  private boolean countsCollected = false;
  private ImmutableMap<Symbol, UsageCounter.CounterData> usageCounts = null;
  private Map<Symbol, Integer> deletedUsages = null;

  /**
   * Copied from NullAway comment. Error Prone requires us to have an empty constructor for each
   * Plugin, in addition to the constructor taking an ErrorProneFlags object. This constructor
   * should not be used anywhere else.
   */
  public XPFlagCleaner() {}

  public XPFlagCleaner(ErrorProneFlags flags) throws ParseException {
    this.flags = flags;
  }

  void init(ErrorProneFlags flags) throws ParseException {
    Optional<String> s = flags.get("Piranha:FlagName");
    if (s.isPresent()) {
      xpFlagName = s.get();
      isTreated = flags.getBoolean("Piranha:IsTreated").orElse(true);
      treatmentGroup = flags.get("Piranha:TreatmentGroup").orElse("").toLowerCase();
    } else {
      throw new ParseException("Piranha:FlagName is missing", 0);
    }

    Optional<String> f = flags.get("Piranha:Config");
    if (f.isPresent()) {
      String configFile = f.get();

      try {
        Path configFilePath = Paths.get(configFile);
        boolean configFileExists = configFilePath.toFile().exists();
        if (!configFileExists) {
          throw new IOException();
        }
        JSONParser parser = new JSONParser();
        JSONObject propertiesJson =
            (JSONObject)
                parser.parse(Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8));
        linkURL = (String) propertiesJson.get("linkURL");
        if (propertiesJson.get("annotations") != null) {
          handledAnnotations.addAll((List<String>) propertiesJson.get("annotations"));
        }
        Set<Map<String, Object>> methodProperties = new HashSet<>();
        if (propertiesJson.get("methodProperties") != null) {
          methodProperties.addAll(
              (List<Map<String, Object>>) propertiesJson.get("methodProperties"));
        } else {
          throw new ParseException("methodProperties not found.", 0);
        }
        Optional<String> argumentIndexOptional = flags.get("Piranha:ArgumentIndexOptional");
        boolean isArgumentIndexOptional = false;
        if (argumentIndexOptional.isPresent()
            && TRUE.equalsIgnoreCase(argumentIndexOptional.get())) {
          isArgumentIndexOptional = true;
        }
        addMethodPropertiesToConfigMethodProperties(methodProperties, isArgumentIndexOptional);
      } catch (IOException fnfe) {
        throw new ParseException("Provided config file is not found", 0);
      } catch (ParseException pe) {
        throw new ParseException("Invalid or incorrectly formatted config file. " + pe, 0);
      } catch (Exception e) {
        throw new ParseException("Some other exception thrown while parsing config", 0);
      }
    } else {
      throw new ParseException("Piranha:Config is missing", 0);
    }
    initialized = true;
  }

  // We call this lazily only when needed, meaning when a symbol usage is first deleted for this
  // Compilation Unit.
  private void computeSymbolCounts(VisitorState visitorState) {
    Preconditions.checkArgument(
        !countsCollected, "This shouldn't be called more than once per Compilation Unit");
    deletedUsages = new LinkedHashMap<>();
    // We count all usages stating at the root of the current compilation unit.
    usageCounts = UsageCounter.getUsageCounts(visitorState, cuPath);
    countsCollected = true;
  }

  private void decrementSymbolUsage(
      Symbol symbol, VisitorState visitorState, SuggestedFix.Builder builder) {
    // Run UsageCounter and check if this is a variable of interest as per that class.
    if (!countsCollected) {
      computeSymbolCounts(visitorState);
    }
    if (!usageCounts.containsKey(symbol)) {
      // Not a variable tracked by UsageCounter or UsageCheckers
      return;
    }
    // Then, update the number of deletions of `symbol`
    int perSymbolDeletedUsages = 1;
    if (deletedUsages.containsKey(symbol)) {
      perSymbolDeletedUsages += deletedUsages.get(symbol);
    }
    deletedUsages.put(symbol, perSymbolDeletedUsages);
    // Finally, check if this number of deletions equals the entire usage count and patch
    // accordingly.
    UsageCounter.CounterData counterData = usageCounts.get(symbol);
    Preconditions.checkArgument(counterData.count >= perSymbolDeletedUsages);
    if (counterData.count == perSymbolDeletedUsages) {
      // Remove the variable declaration.
      builder.delete(counterData.declaration);
    }
  }

  private void decrementAllSymbolUsages(
      Tree tree, VisitorState visitorState, SuggestedFix.Builder builder) {
    Map<Symbol, Integer> deletedUsages = UsageCounter.getRawUsageCounts(tree);
    for (Symbol s : deletedUsages.keySet()) {
      decrementSymbolUsage(s, visitorState, builder);
    }
  }

  @Override
  public Description matchCompilationUnit(
      CompilationUnitTree compilationUnitTree, VisitorState visitorState) {
    if (!initialized && !disabled) {
      try {
        init(flags);
      } catch (ParseException pe) {
        disabled = true;
      }
    }
    if (countsCollected) {
      // Clear out this info
      countsCollected = false;
      usageCounts = null;
      deletedUsages = null;
    }
    cuPath = visitorState.getPath();
    return Description.NO_MATCH;
  }

  @Override
  public String linkUrl() {
    return linkURL;
  }

  private void addMethodPropertiesToConfigMethodProperties(
      Set<Map<String, Object>> methodProperties, boolean isArgumentIndexOptional)
      throws ParseException {
    for (Map<String, Object> methodProperty : methodProperties) {
      String methodName = getValueStringFromMap(methodProperty, METHOD_NAME_KEY);
      if (methodName != null && getValueStringFromMap(methodProperty, FLAG_TYPE_KEY) != null) {
        if (!isArgumentIndexOptional && getArgumentIndexFromMap(methodProperty) == null) {
          throw new ParseException(
              "methodProperty did not have argumentIndex. Use Piranha:ArgumentIndexOptional if required. Check:"
                  + methodProperty,
              0);
        }
        List<Map<String, Object>> methodPropertiesForMethodName =
            configMethodProperties.get(methodName);
        if (methodPropertiesForMethodName == null) {
          methodPropertiesForMethodName = new ArrayList<>();
          configMethodProperties.put(methodName, methodPropertiesForMethodName);
        }
        methodPropertiesForMethodName.add(methodProperty);
      } else {
        throw new ParseException(
            "methodProperty did not have methodName or flagType. Check:" + methodProperty, 0);
      }
    }
  }

  /* Returns the appropriate XP API, if any, as given by the expression */
  private API getXPAPI(ExpressionTree et) {
    et = ASTHelpers.stripParentheses(et);
    Kind k = et.getKind();
    if (k.equals(Tree.Kind.METHOD_INVOCATION)) {
      MethodInvocationTree mit = (MethodInvocationTree) et;
      if (!mit.getMethodSelect().getKind().equals(Kind.MEMBER_SELECT)) {
        return API.UNKNOWN;
      }
      MemberSelectTree mst = (MemberSelectTree) mit.getMethodSelect();
      String methodName = mst.getIdentifier().toString();
      List<Map<String, Object>> methodPropertiesForMethodName =
          configMethodProperties.get(methodName);
      if (methodPropertiesForMethodName != null) {
        return getXPAPI(mit, methodPropertiesForMethodName);
      }
    }
    return API.UNKNOWN;
  }

  private API getXPAPI(
      MethodInvocationTree mit, List<Map<String, Object>> methodPropertiesForMethodName) {
    for (Map<String, Object> currentMethodProperties : methodPropertiesForMethodName) {
      Integer argumentIndex = getArgumentIndexFromMap(currentMethodProperties);

      // when argumentIndex is specified, if mit's argument at argIndex doesn't match xpFlagName,
      // skip to next method property map
      if (argumentIndex != null) {
        if (argumentIndex < mit.getArguments().size()) {
          ExpressionTree argTree = mit.getArguments().get(argumentIndex);
          Symbol argSym = ASTHelpers.getSymbol(argTree);
          if (!isArgumentMatchesFlagName(argTree, argSym)) {
            continue;
          }
        } else {
          continue;
        }
      }
      MemberSelectTree mst = ((MemberSelectTree) mit.getMethodSelect());
      // when returnType is specified, check if mst's return type matches it
      // if it's not a match, skip to next method property map
      String returnType = getValueStringFromMap(currentMethodProperties, RETURN_TYPE_STRING);
      if (returnType != null) {
        String mReturn = ASTHelpers.getReturnType(mst).toString();
        if (!returnType.equalsIgnoreCase(mReturn)) {
          continue;
        }
      }
      // when receiverType is specified, check if mst's receiver type matches it
      // if it's not a match, skip to next method property map
      String receiverType = getValueStringFromMap(currentMethodProperties, RECEIVER_TYPE_STRING);
      if (receiverType != null) {
        String mReceive = ASTHelpers.getReceiverType(mst).toString();
        if (!receiverType.equalsIgnoreCase(mReceive)) {
          continue;
        }
      }
      return flagTypeToAPIMap.getOrDefault(
          (String) currentMethodProperties.get(FLAG_TYPE_KEY), API.UNKNOWN);
    }
    return API.UNKNOWN;
  }

  private static Map<String, API> initializeFlagTypeToAPIMap() {
    Map<String, API> map = new HashMap<>();
    map.put("treated", API.IS_TREATED);
    map.put("control", API.IS_CONTROL);
    map.put("empty", API.DELETE_METHOD);
    map.put("treatmentGroup", API.IS_TREATMENT_GROUP_CHECK);
    return Collections.unmodifiableMap(map);
  }

  private boolean isArgumentMatchesFlagName(ExpressionTree argTree, Symbol argSym) {
    return (isLiteralTreeAndMatchesFlagName(argTree)
        || isVarSymbolAndMatchesFlagName(argSym)
        || isSymbolAndMatchesFlagName(argSym));
  }

  /**
   * Checks for {@link Symbol} and the flag name
   *
   * @param argSym
   * @return True if matches. Otherwise false
   */
  private boolean isSymbolAndMatchesFlagName(Symbol argSym) {
    return argSym != null && (argSym.equals(xpSym) || argSym.toString().equals(xpFlagName));
  }

  /**
   * Checks for {@link com.sun.tools.javac.code.Symbol.VarSymbol} and the flag name
   *
   * @param argSym
   * @return True if matches. Otherwise false
   */
  private boolean isVarSymbolAndMatchesFlagName(Symbol argSym) {
    return argSym instanceof Symbol.VarSymbol
        && ((Symbol.VarSymbol) argSym).getConstantValue() != null
        && ((Symbol.VarSymbol) argSym).getConstantValue().equals(xpFlagName);
  }

  /**
   * Checks for {@link LiteralTree} and the flag name
   *
   * @param arg
   * @return True if matches. Otherwise false
   */
  private boolean isLiteralTreeAndMatchesFlagName(ExpressionTree arg) {
    return arg instanceof LiteralTree
        && ((LiteralTree) arg).getValue() != null
        && ((LiteralTree) arg).getValue().equals(xpFlagName);
  }

  private String stripBraces(String s) {
    if (s.startsWith("{")) {
      s = s.substring(1);
      if (s.endsWith("}")) {
        s = s.substring(0, s.length() - 1);
      }
    }
    return s.trim();
  }

  /* this method checks for whether the enclosing source is already replaced.
   * e.g., when if(cond) { ... } is processed, it may be replaced with the then body.
   * but the checker subsequently processes cond, which can have its own matching.
   * The check for overLaps will ensure that this matching (and possible replacement) of
   * the internal expression does not happen.
   */
  private boolean overLaps(Tree t, VisitorState visitorState) {
    if (endPos != DONTCARE && visitorState.getEndPosition(t) <= endPos) {
      return true;
    } else {
      endPos = DONTCARE;
      return false;
    }
  }

  /* Evaluate the expression by handling various expression kinds.
   * Ensure that the appropriate XP API is also evaluated in the process.
   */
  private Value evalExpr(ExpressionTree tree, VisitorState state) {
    if (tree == null) {
      return Value.BOT;
    }

    Kind k = tree.getKind();

    if (k.equals(Kind.PARENTHESIZED)) {
      ParenthesizedTree pt = (ParenthesizedTree) tree;
      Value v = evalExpr(pt.getExpression(), state);
      return v;
    }

    if (k.equals(Kind.BOOLEAN_LITERAL)) {
      LiteralTree lt = (LiteralTree) tree;
      if (lt.getValue().equals(Boolean.TRUE)) {
        return Value.TRUE;
      }
      if (lt.getValue().equals(Boolean.FALSE)) {
        return Value.FALSE;
      }
    }

    if (k.equals(Kind.LOGICAL_COMPLEMENT)) {
      UnaryTree ut = (UnaryTree) tree;
      Value e = evalExpr(ut.getExpression(), state);
      if (e.equals(Value.FALSE)) {
        return Value.TRUE;
      }
      if (e.equals(Value.TRUE)) {
        return Value.FALSE;
      }
    }

    if (k.equals(Kind.METHOD_INVOCATION)) {
      API api = getXPAPI(tree);
      if (api.equals(API.IS_TREATED)) {
        return isTreated ? Value.TRUE : Value.FALSE;
      } else if (api.equals(API.IS_CONTROL)) {
        return isTreated ? Value.FALSE : Value.TRUE;
      } else if (api.equals(API.IS_TREATMENT_GROUP_CHECK)) {
        return evalTreatmentGroupCheck((MethodInvocationTree) tree) ? Value.TRUE : Value.FALSE;
      }
    }

    if (k.equals(Kind.CONDITIONAL_AND) || k.equals(Kind.CONDITIONAL_OR)) {
      BinaryTree bt = (BinaryTree) tree;

      Value l = evalExpr(bt.getLeftOperand(), state);
      Value r = evalExpr(bt.getRightOperand(), state);

      if (k.equals(Kind.CONDITIONAL_OR)) {
        if (l.equals(Value.TRUE) || r.equals(Value.TRUE)) {
          return Value.TRUE;
        }

        if (l.equals(Value.FALSE) && r.equals(Value.FALSE)) {
          return Value.FALSE;
        }

      } else if (k.equals(Kind.CONDITIONAL_AND)) {
        if (l.equals(Value.TRUE) && r.equals(Value.TRUE)) {
          return Value.TRUE;
        }

        if (l.equals(Value.FALSE) || r.equals(Value.FALSE)) {
          return Value.FALSE;
        }
      }
    }

    return Value.BOT;
  }

  /**
   * A utility method that simulated evaluation of isInTreatmentGroup and similar methods.
   *
   * @param methodInvocationTree The method call to isInTreatmentGroup
   * @return true if the second argument matches the treatmentGroup passed to this checker
   */
  private boolean evalTreatmentGroupCheck(MethodInvocationTree methodInvocationTree) {
    Preconditions.checkArgument(
        methodInvocationTree.getArguments().size() == 2,
        "Treatment group checks (e.g. isInTreatmentGroup) must take two arguments");
    ExpressionTree arg = methodInvocationTree.getArguments().get(1);
    Symbol argSym = ASTHelpers.getSymbol(arg);
    return (argSym != null && argSym.toString().toLowerCase().equals(treatmentGroup));
  }

  /* A utility method to update code corresponding to an expression
   *  used for various expression kinds
   */
  private Description updateCode(
      Value v, ExpressionTree tree, ExpressionTree expr, VisitorState state) {
    boolean update = false;
    String replacementString = "";

    if (v.equals(Value.TRUE)) {
      update = true;
      replacementString = TRUE;
    } else if (v.equals(Value.FALSE)) {
      update = true;
      replacementString = FALSE;
    }

    if (update) {
      Description.Builder builder = buildDescription(tree);
      SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
      fixBuilder.replace(expr, replacementString);
      decrementAllSymbolUsages(expr, state, fixBuilder);
      builder.addFix(fixBuilder.build());
      endPos = state.getEndPosition(expr);
      return builder.build();
    }
    return Description.NO_MATCH;
  }

  private boolean isTreatmentGroupEnum(Symbol.ClassSymbol enumSym) {
    // Filter out some generic names, like CONTROL to make sure we don't match the wrong
    // TreatmentGroup
    if (COMMON_GROUP_NAMES.contains(treatmentGroup)) {
      return false;
    }
    for (Symbol fsym : enumSym.getEnclosedElements()) {
      if (fsym.getKind().equals(ElementKind.ENUM_CONSTANT)
          && fsym.name.toString().toLowerCase().equals(treatmentGroup)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Description matchClass(ClassTree classTree, VisitorState visitorState) {
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(classTree);
    if (classSymbol.getKind().equals(ElementKind.ENUM) && isTreatmentGroupEnum(classSymbol)) {
      treatmentGroupsEnum = classSymbol.fullname.toString();
      if (classSymbol.getNestingKind().isNested()) {
        return buildDescription(classTree).addFix(SuggestedFix.delete(classTree)).build();
      } else {
        String emptyEnum =
            PiranhaUtils.DELETE_REQUEST_COMMENT
                + "enum "
                + classSymbol.getSimpleName().toString()
                + " { }";
        return buildDescription(classTree)
            .addFix(SuggestedFix.replace(classTree, emptyEnum))
            .build();
      }
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchImport(ImportTree importTree, VisitorState visitorState) {
    if (importTree.isStatic()) {
      Tree importIdentifier = importTree.getQualifiedIdentifier();
      if (importIdentifier.getKind().equals(Kind.MEMBER_SELECT)) {
        MemberSelectTree memberSelectTree = (MemberSelectTree) importIdentifier;
        if (memberSelectTree.getIdentifier().toString().endsWith(xpFlagName)
            || (treatmentGroupsEnum != null
                && memberSelectTree.getExpression().toString().startsWith(treatmentGroupsEnum))) {
          return buildDescription(importTree)
              .addFix(SuggestedFix.replace(importTree, "", 0, 1))
              .build();
        } else if (treatmentGroup.length() > 0 && treatmentGroupsEnum == null) {
          // Check if this import is for values in the same enum that includes the treatmentGroup
          Symbol importSymbol = ASTHelpers.getSymbol(memberSelectTree.getExpression());
          if (importSymbol.getKind().equals(ElementKind.ENUM)
              && isTreatmentGroupEnum((Symbol.ClassSymbol) importSymbol)) {
            treatmentGroupsEnum = ((Symbol.ClassSymbol) importSymbol).fullname.toString();
            return buildDescription(importTree)
                .addFix(SuggestedFix.replace(importTree, "", 0, 1))
                .build();
          }
        }
      }
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchAssignment(AssignmentTree tree, VisitorState state) {
    if (tree.getExpression().getKind().equals(Kind.BOOLEAN_LITERAL) || overLaps(tree, state)) {
      return Description.NO_MATCH;
    }
    Value x = evalExpr(tree.getExpression(), state);
    return updateCode(x, tree, tree.getExpression(), state);
  }

  @Override
  public Description matchUnary(UnaryTree tree, VisitorState state) {
    if (overLaps(tree, state)) {
      return Description.NO_MATCH;
    }
    Value x = evalExpr(tree, state);
    return updateCode(x, tree, tree, state);
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    if (overLaps(tree, state)) {
      return Description.NO_MATCH;
    }

    Value x = evalExpr(tree, state);
    return updateCode(x, tree, tree, state);
  }

  @Override
  public Description matchBinary(BinaryTree tree, VisitorState state) {
    if (overLaps(tree, state)) {
      return Description.NO_MATCH;
    }

    Value x = evalExpr(tree, state);
    Description d = updateCode(x, tree, tree, state);
    if (!d.equals(Description.NO_MATCH)) {
      return d;
    }

    ExpressionTree deletedSubTree = null;
    ExpressionTree remainingSubTree = null;
    Value l = evalExpr(tree.getLeftOperand(), state);
    Value r = evalExpr(tree.getRightOperand(), state);
    if (tree.getKind().equals(Kind.CONDITIONAL_AND)) {
      if (l.equals(Value.TRUE)) {
        deletedSubTree = tree.getLeftOperand();
        remainingSubTree = tree.getRightOperand();
      } else if (r.equals(Value.TRUE)) {
        deletedSubTree = tree.getRightOperand();
        remainingSubTree = tree.getLeftOperand();
      }
    } else if (tree.getKind().equals(Kind.CONDITIONAL_OR)) {
      if (l.equals(Value.FALSE)) {
        deletedSubTree = tree.getLeftOperand();
        remainingSubTree = tree.getRightOperand();
      } else if (r.equals(Value.FALSE)) {
        deletedSubTree = tree.getRightOperand();
        remainingSubTree = tree.getLeftOperand();
      }
    }

    if (deletedSubTree != null) {
      Description.Builder builder = buildDescription(tree);
      SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
      fixBuilder.replace(tree, remainingSubTree.toString());
      decrementAllSymbolUsages(deletedSubTree, state, fixBuilder);
      builder.addFix(fixBuilder.build());

      endPos = state.getEndPosition(tree);
      return builder.build();
    }

    return Description.NO_MATCH;
  }

  @Override
  public Description matchExpressionStatement(ExpressionStatementTree tree, VisitorState state) {
    if (overLaps(tree, state)) {
      return Description.NO_MATCH;
    }

    if (tree.getExpression().getKind().equals(Kind.METHOD_INVOCATION)) {
      MethodInvocationTree mit = (MethodInvocationTree) tree.getExpression();
      API api = getXPAPI(mit);
      if (api.equals(API.DELETE_METHOD)) {
        Description.Builder builder = buildDescription(tree);
        SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
        fixBuilder.delete(tree);
        decrementAllSymbolUsages(tree, state, fixBuilder);
        builder.addFix(fixBuilder.build());
        endPos = state.getEndPosition(tree);
        return builder.build();
      }
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchReturn(ReturnTree tree, VisitorState state) {
    if (overLaps(tree, state)) {
      return Description.NO_MATCH;
    }
    ExpressionTree et = tree.getExpression();
    if (et != null && et.getKind().equals(Kind.BOOLEAN_LITERAL)) {
      return Description.NO_MATCH;
    }

    Value x = evalExpr(et, state);
    boolean update = false;
    String replacementString = EMPTY;

    if (x.equals(Value.TRUE)) {
      update = true;
      replacementString = TRUE;
    } else if (x.equals(Value.FALSE)) {
      update = true;
      replacementString = FALSE;
    }

    if (update) {
      Description.Builder builder = buildDescription(tree);
      SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
      fixBuilder.replace(et, replacementString);
      decrementAllSymbolUsages(et, state, fixBuilder);
      builder.addFix(fixBuilder.build());
      endPos = state.getEndPosition(tree);
      return builder.build();
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    Symbol sym = FindIdentifiers.findIdent(xpFlagName, state);
    // Check if this is the flag definition and remove it.
    if (sym != null && sym.isEnum() && sym.equals(ASTHelpers.getSymbol(tree))) {
      xpSym = sym;
      // Remove the flag symbol. This only works because the error prone patch is applied once
      // after all files have been analyzed, otherwise targets that use the flag but haven't been
      // cleaned up would be broken. We use replace with a position adjustment, to get rid of the
      // trailing "," if present on the parent.
      String enumAsStr = state.getSourceForNode(state.getPath().getParentPath().getLeaf());
      String varAsStrWithComma = tree.getName().toString() + ",";
      if (enumAsStr.contains(varAsStrWithComma)) {
        return buildDescription(tree).addFix(SuggestedFix.replace(tree, "", 0, 1)).build();
      } else {
        // Fallback for single/last enum variable detection
        return buildDescription(tree).addFix(SuggestedFix.delete(tree)).build();
      }
    } else if (sym == null
        && tree != null
        && ASTHelpers.getSymbol(tree) != null
        && xpFlagName.equals(ASTHelpers.getSymbol(tree).getConstantValue())) {
      return buildDescription(tree).addFix(SuggestedFix.delete(tree)).build();
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {

    for (String name : handledAnnotations) {
      AnnotationTree at =
          ASTHelpers.getAnnotationWithSimpleName(tree.getModifiers().getAnnotations(), name);

      if (at != null) {
        for (ExpressionTree et : at.getArguments()) {
          if (et.getKind() == Kind.ASSIGNMENT) {
            AssignmentTree assn = (AssignmentTree) et;
            if (assn.getExpression().toString().endsWith(xpFlagName)) {
              Description.Builder builder = buildDescription(tree);
              SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
              if (isTreated) {
                fixBuilder.delete(at);
                decrementAllSymbolUsages(at, state, fixBuilder);
              } else {
                fixBuilder.delete(tree);
                decrementAllSymbolUsages(tree, state, fixBuilder);
              }
              builder.addFix(fixBuilder.build());
              return builder.build();
            }
          }
        }
      }
    }

    return Description.NO_MATCH;
  }

  @Override
  public Description matchConditionalExpression(
      ConditionalExpressionTree tree, VisitorState state) {
    if (overLaps(tree, state)) {
      return Description.NO_MATCH;
    }
    ExpressionTree et = tree.getCondition();
    Value x = evalExpr(et, state);
    boolean update = false;
    String replacementString = EMPTY;

    ExpressionTree removedBranch = null;
    if (x.equals(Value.TRUE)) {
      update = true;
      replacementString = state.getSourceForNode(tree.getTrueExpression());
      removedBranch = tree.getFalseExpression();
    } else if (x.equals(Value.FALSE)) {
      update = true;
      replacementString = state.getSourceForNode(tree.getFalseExpression());
      removedBranch = tree.getTrueExpression();
    }

    if (update) {
      Description.Builder builder = buildDescription(tree);
      SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
      fixBuilder.replace(tree, stripBraces(replacementString));
      decrementAllSymbolUsages(et, state, fixBuilder);
      decrementAllSymbolUsages(removedBranch, state, fixBuilder);
      builder.addFix(fixBuilder.build());
      endPos = state.getEndPosition(tree);
      return builder.build();
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchIf(IfTree ifTree, VisitorState visitorState) {

    if (overLaps(ifTree, visitorState)) {
      return Description.NO_MATCH;
    }

    // Ignore if this is part of an else-if clause, we will process it all at once
    // when we get to the topmost if.
    Tree parentTree = visitorState.getPath().getParentPath().getLeaf();
    if (parentTree.getKind().equals(Kind.IF)) {
      Tree parentElseChild = ((IfTree) parentTree).getElseStatement();
      if (parentElseChild != null && parentElseChild.equals(ifTree)) {
        return Description.NO_MATCH;
      }
    }

    ParenthesizedTree parenTree = (ParenthesizedTree) ifTree.getCondition();
    Value x = evalExpr(parenTree.getExpression(), visitorState);
    boolean update = false;
    String replacementString = EMPTY;
    String replacementPrefix = EMPTY;
    boolean lastStmtIsReturn = false;
    Set<StatementTree> removedBranches = new LinkedHashSet<StatementTree>();
    // This code simplifies a nested if {...} (else if {...})* (else {...})? three all at once
    IfTree subIfTree = ifTree;
    boolean recurse;
    do {
      recurse = false;
      StatementTree elseStatement = subIfTree.getElseStatement();
      if (x.equals(Value.TRUE)) {
        update = true;
        if (elseStatement != null) {
          removedBranches.add(elseStatement);
        }
        replacementString = visitorState.getSourceForNode(subIfTree.getThenStatement());
        lastStmtIsReturn = endsWithReturn(ifTree.getThenStatement());
      } else if (x.equals(Value.FALSE)) {
        update = true;
        if (elseStatement != null) {
          removedBranches.add(subIfTree.getThenStatement());
          replacementString = visitorState.getSourceForNode(elseStatement);
          if (elseStatement.getKind().equals(Kind.IF)) {
            // Keep going, in case we can eliminate more of the branches of the
            // nested if.
            recurse = true;
            subIfTree = (IfTree) elseStatement;
            ParenthesizedTree pT = (ParenthesizedTree) subIfTree.getCondition();
            x = evalExpr(pT, visitorState);
          } else {
            lastStmtIsReturn = endsWithReturn(subIfTree.getElseStatement());
          }
        }
      } else {
        // The condition doesn't simplify to a constant, but the condition to some nested "else if"
        // might.
        if (elseStatement != null && elseStatement.getKind().equals(Kind.IF)) {
          // Copy the initial if condition (don't mark as needing update yet)
          replacementPrefix += "if " + visitorState.getSourceForNode(subIfTree.getCondition());
          replacementPrefix +=
              visitorState.getSourceForNode(subIfTree.getThenStatement()) + " else ";
          // Then recurse on the else case
          recurse = true;
          subIfTree = (IfTree) elseStatement;
          ParenthesizedTree pT = (ParenthesizedTree) subIfTree.getCondition();
          x = evalExpr(pT, visitorState);
        }
      }
    } while (recurse);

    if (update) {
      if (!replacementPrefix.equals(EMPTY)) {
        replacementString = replacementPrefix + replacementString;
      } else {
        replacementString = stripBraces(replacementString);
      }
      Description.Builder builder = buildDescription(ifTree);
      // We use SuggestedFix.Builder to AND-compose fixes. Note that calling
      // Description.Builder.addFix(...) multiple times is interpreted as OR-composing
      // multiple candidate fixes (i.e. "Fix by doing A or B or C" where we want
      // "Fix by doing A and B and C")
      SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
      if (lastStmtIsReturn) {
        // find the parent, and if it's a BlockTree, replace the if statement and delete any
        // subsequent statements
        Tree parent = visitorState.getPath().getParentPath().getLeaf();
        // note that parent may not be a block, e.g., if we have a parent if statement with no
        // braces for the body
        if (parent instanceof BlockTree) {
          BlockTree block = (BlockTree) parent;
          boolean foundIf = false;
          for (StatementTree stmt : block.getStatements()) {
            if (foundIf) {
              // We are past the if statement, so everything after this will be deleted,
              // decrement all usage counts accordingly
              decrementAllSymbolUsages(stmt, visitorState, fixBuilder);
              fixBuilder.delete(stmt);
            } else if (!stmt.equals(ifTree)) {
              // preceding statement, keep it
              continue;
            } else {
              // we reached the if
              for (StatementTree removedBranch : removedBranches) {
                decrementAllSymbolUsages(removedBranch, visitorState, fixBuilder);
              }
              decrementAllSymbolUsages(ifTree.getCondition(), visitorState, fixBuilder);
              fixBuilder.replace(ifTree, replacementString);
              // elide the remaining statements
              foundIf = true;
            }
          }
          // Usage counts already decremented above.
          endPos = visitorState.getEndPosition(block);
          return builder.addFix(fixBuilder.build()).build();
        }
      }
      fixBuilder.replace(ifTree, replacementString);
      for (StatementTree removedBranch : removedBranches) {
        decrementAllSymbolUsages(removedBranch, visitorState, fixBuilder);
      }
      decrementAllSymbolUsages(ifTree.getCondition(), visitorState, fixBuilder);
      endPos = visitorState.getEndPosition(ifTree);
      return builder.addFix(fixBuilder.build()).build();
    }

    return Description.NO_MATCH;
  }

  /** Is the statement a return statement, or is it a block that ends in a return statement? */
  private boolean endsWithReturn(StatementTree stmt) {
    if (stmt instanceof ReturnTree) {
      return true;
    }
    if (stmt instanceof BlockTree) {
      List<? extends StatementTree> statements = ((BlockTree) stmt).getStatements();
      return statements.size() > 0 && statements.get(statements.size() - 1) instanceof ReturnTree;
    }
    return false;
  }

  /**
   * Utility method. Checks whether the value associated to a given map and given key is a non-empty
   * string.
   *
   * @param map - map corresponding to a method property
   * @param key - key to check the corresponding value
   * @return String if value is a non-empty string, null otherwise
   */
  private static String getValueStringFromMap(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof String && !value.equals(EMPTY)) {
      return String.valueOf(value);
    }
    return null;
  }

  /**
   * Utility method. Checks whether the argumentIndex key of a method property map is a non-negative
   * integer.
   *
   * @param map - map corresponding to a method property
   * @return argumentIndex if argument index is a non-negative integer, null otherwise
   */
  private static Integer getArgumentIndexFromMap(Map<String, Object> map) {
    Object value = map.get(ARGUMENT_INDEX_KEY);
    if (value instanceof Long) {
      int argumentIndex = ((Long) value).intValue();
      if (argumentIndex >= ZERO) {
        return argumentIndex;
      }
    }
    return null;
  }
}
