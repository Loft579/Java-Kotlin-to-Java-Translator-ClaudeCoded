// Generated from Kotlin.g4 by ANTLR 4.13.2
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link KotlinParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface KotlinVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link KotlinParser#prog}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProg(KotlinParser.ProgContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#packageList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackageList(KotlinParser.PackageListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#packageP}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackageP(KotlinParser.PackagePContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#definitionP}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefinitionP(KotlinParser.DefinitionPContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#importP}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImportP(KotlinParser.ImportPContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#packagename}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackagename(KotlinParser.PackagenameContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList(KotlinParser.ListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#start}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStart(KotlinParser.StartContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#interfaceList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterfaceList(KotlinParser.InterfaceListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassList(KotlinParser.ClassListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classDecl}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassDecl(KotlinParser.ClassDeclContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classcompound}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClasscompound(KotlinParser.ClasscompoundContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classStmtList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassStmtList(KotlinParser.ClassStmtListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassStmt(KotlinParser.ClassStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#initBlock}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInitBlock(KotlinParser.InitBlockContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#getter}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGetter(KotlinParser.GetterContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#extendsORimplements}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExtendsORimplements(KotlinParser.ExtendsORimplementsContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classORinterface}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassORinterface(KotlinParser.ClassORinterfaceContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#interfaceDecl}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterfaceDecl(KotlinParser.InterfaceDeclContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#funcSignature}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFuncSignature(KotlinParser.FuncSignatureContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#declGlobalList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclGlobalList(KotlinParser.DeclGlobalListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#globalDecl}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGlobalDecl(KotlinParser.GlobalDeclContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#visibility}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVisibility(KotlinParser.VisibilityContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#funcList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFuncList(KotlinParser.FuncListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunction(KotlinParser.FunctionContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#abstractFunction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAbstractFunction(KotlinParser.AbstractFunctionContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#funcassign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFuncassign(KotlinParser.FuncassignContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#funcType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFuncType(KotlinParser.FuncTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#declID}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclID(KotlinParser.DeclIDContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#declTypeId}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclTypeId(KotlinParser.DeclTypeIdContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#noassign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNoassign(KotlinParser.NoassignContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#withassign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWithassign(KotlinParser.WithassignContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classparamList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassparamList(KotlinParser.ClassparamListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#classParamID}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassParamID(KotlinParser.ClassParamIDContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#keyword}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitKeyword(KotlinParser.KeywordContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#paramID}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParamID(KotlinParser.ParamIDContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#paramList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParamList(KotlinParser.ParamListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#compoundStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCompoundStmt(KotlinParser.CompoundStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#stmtList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStmtList(KotlinParser.StmtListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#stmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStmt(KotlinParser.StmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#jumpStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJumpStmt(KotlinParser.JumpStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#lambdaexpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLambdaexpression(KotlinParser.LambdaexpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#lambdaTerm}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLambdaTerm(KotlinParser.LambdaTermContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#forStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitForStmt(KotlinParser.ForStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#callStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCallStmt(KotlinParser.CallStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#whileStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhileStmt(KotlinParser.WhileStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#whenStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhenStmt(KotlinParser.WhenStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#whenexpr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhenexpr(KotlinParser.WhenexprContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#whenKeys}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhenKeys(KotlinParser.WhenKeysContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#ifStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIfStmt(KotlinParser.IfStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#withElse}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWithElse(KotlinParser.WithElseContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#noElse}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNoElse(KotlinParser.NoElseContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCondition(KotlinParser.ConditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#explicitRange}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplicitRange(KotlinParser.ExplicitRangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#identifierStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifierStmt(KotlinParser.IdentifierStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#declare}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclare(KotlinParser.DeclareContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#assignexpr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignexpr(KotlinParser.AssignexprContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#shortIf}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShortIf(KotlinParser.ShortIfContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#retStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRetStmt(KotlinParser.RetStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpression(KotlinParser.ExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#chain}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChain(KotlinParser.ChainContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#chainStart}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChainStart(KotlinParser.ChainStartContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#callHead}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCallHead(KotlinParser.CallHeadContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#chainLink}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChainLink(KotlinParser.ChainLinkContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#memberName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMemberName(KotlinParser.MemberNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#exprID}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExprID(KotlinParser.ExprIDContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#listID}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitListID(KotlinParser.ListIDContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#logexpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLogexpression(KotlinParser.LogexpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#logsides}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLogsides(KotlinParser.LogsidesContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#logsuffix}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLogsuffix(KotlinParser.LogsuffixContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#call}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall(KotlinParser.CallContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#callName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCallName(KotlinParser.CallNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType(KotlinParser.TypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpr(KotlinParser.ExprContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#argList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArgList(KotlinParser.ArgListContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#stringLiteral}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringLiteral(KotlinParser.StringLiteralContext ctx);
	/**
	 * Visit a parse tree produced by {@link KotlinParser#num}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNum(KotlinParser.NumContext ctx);
}