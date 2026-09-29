/* Kotlin Basic Syntax */
grammar Kotlin;

//Parser rules
// EOF matters: without it the parser happily stops at the first construct it does
// not know, leaves the rest of the file unconsumed and reports no error, so
// anything unsupported was silently dropped instead of failing.
prog: packageList? list? EOF;
packageList:packageP+;
packageP: definitionP|importP;
definitionP: 'package' packagename;
importP: 'import' packagename ('.''*')?;
packagename:ID ('.'ID)*;

list:start|list start;

start: declGlobalList|funcList|classList|interfaceList;

interfaceList:interfaceDecl+;

classList:classDecl+;
// The body is optional: 'class Counter(var n: Int)' with only a primary constructor
// is ordinary Kotlin and used to be a syntax error.
// Both the parameter list and the body are optional: 'class Holder { ... }' with no
// primary constructor is as common as the parenthesised form.
classDecl:visibility? ('abstract'|'open')? 'class' ID ('(' classparamList? ')')?(':'extendsORimplements)? classcompound?;
classcompound:'{'classStmtList?'}';

classStmtList:classStmt | classStmtList classStmt;
classStmt:stmt|getter|initBlock;
// An init block is constructor code, so its statements are appended to the
// generated constructor.
initBlock:'init' compoundStmt;
/* The body is an assignexpr, not just an expr: 'get() = when (n) { ... }' and
   'get() = if (n > 0) "pos" else "neg"' are how getters are usually written. */
getter:visibility? ('override'|'open')? keyword ID ':' (type('?')?|('List'|'Set')'<'type('?')?'>') 'get''('')' '=' assignexpr;
extendsORimplements:classORinterface| extendsORimplements','classORinterface;
classORinterface:ID|ID'('argList?')';

// A method in a Kotlin interface has no body and no 'abstract' keyword, which the
// 'function' rule cannot express, so interfaces could only declare properties.
// Members in any order: requiring every property before every method is a rule
// Kotlin does not have, and it rejected perfectly ordinary interfaces.
// A property with a 'get() =' carries an implementation, as a method body does.
interfaceDecl:visibility? 'interface' ID (':'extendsORimplements)? '{'(getter|declID|function|funcSignature)*'}';
funcSignature: visibility? ('override'|'open')? 'fun' memberName '(' paramList? ')' funcType?;

declGlobalList:(globalDecl)+;
// Visibility and 'const' are accepted wherever Kotlin allows them. Java needs no
// 'const' (a val is already final) and has no counterpart for 'internal', which
// becomes public in a single generated class.
globalDecl: visibility? 'const'? withassign;
visibility: 'private'|'protected'|'internal'|'public';
funcList: function+;

function: visibility? ('override'|'open')? 'fun' memberName '(' paramList? ')' funcType? (compoundStmt|funcassign)|abstractFunction;
abstractFunction: 'abstract''fun' memberName '(' paramList?')' funcType?;

funcassign:'='(assignexpr|withElse|whenStmt);

// A function may return a collection: 'fun items(): List<String>' was a syntax error
// because only the plain type names were accepted here.
funcType: ':'(type|('List'|'Set')'<'type'>')('?')?;

declID:visibility? ('override')? ('lateinit')? declTypeId;
declTypeId:withassign|noassign;
// The '?' is allowed wherever a type is written, not only on a return type: a
// nullable declaration like 'var name: String? = null' is everyday Kotlin.
// The colon belongs outside the group: written inside only the plain-type branch, a
// declaration such as 'val items: List<String>' could not parse at all.
noassign:keyword ID ':' (type('?')?|('List'|'Set')'<'type('?')?'>'('?')?);
withassign:noassign'='assignexpr
	|keyword ID '=' assignexpr;
classparamList: classParamID (',' classParamID)* ','?;
// 'override val name: String' in the primary constructor is how a class satisfies a
// property declared by an interface it implements.
classParamID:visibility? 'override'? (paramID|keyword paramID);
keyword:'val'|'var';

paramID:ID':'(type('?')?|('List'|'Set')'<'type('?')?'>'('?')?);
paramList: paramID (',' paramID)* ','?;

compoundStmt: '{' stmtList? '}';

stmtList: stmt+;

stmt: callStmt
	|jumpStmt
	|retStmt
	|expression
	|ifStmt
	|identifierStmt
	|logexpression
	|function
	|forStmt
	|whileStmt
	|whenStmt
	|lambdaexpression;
// Read as statements of their own: as bare names they would reach the emitter as
// an expression with no effect, and only the spelling being the same in Java would
// have kept them working.
jumpStmt:('break'|'continue');
lambdaexpression:ID lambdaTerm+;
// The body is a whole expression and the parameter may be named: '{ x -> x > 1 }'
// as well as '{ it > 1 }'. Only a call or a bare name used to be accepted.
lambdaTerm:'.'ID'{'(ID '->')? assignexpr'}';
// The sequence may be any expression, not just a name: 'for (x in items())'.
forStmt:'for''('ID 'in' (exprID|explicitRange|call|chain|listID|lambdaexpression) ')'compoundStmt;

callStmt: call;

whileStmt:'while''(' condition ')'compoundStmt;

// The subject may be any expression, not just a name: 'when (node.next)'.
whenStmt:'when' '(' expr ')''{' whenexpr+ '}'
	|'when''{'whenexpr+'}';

// A branch may list several keys ('6, 7 -> ...'), which become an OR of the tests.
// A branch body may be a block: 'n -> { ... }' with several statements in it is as
// common as the one-expression form.
whenexpr:whenKeys '->' (expr|retStmt|jumpStmt|compoundStmt)
	|'else''->' (expr|retStmt|jumpStmt|compoundStmt);
whenKeys: expr (','expr)*;

ifStmt: withElse|noElse|shortIf;
// 'else if' chains: the else branch may be another if, not only a block.
withElse: 'if''(' condition ')' compoundStmt 'else' (compoundStmt|ifStmt);

noElse:'if''(' condition ')' compoundStmt ;
condition: call 
	|logexpression;

// 'until' is the exclusive form of a range and is as common as '..' in loops.
explicitRange: (expression|call) ('..'|'downTo'|'until')(expression|call) ('step' (expression|call))?;
// The target may be a whole path: reading 'car.engine.power' worked while assigning
// to it did not, because only the two-segment exprID was accepted here.
// 'xs[i] = v' too: reading an element was accepted while writing one was not.
identifierStmt:declare|(chain|exprID|listID) ('+='|'*='|'/='|'-='|'%='|'=') assignexpr;
declare:declID;


// 'when' as a value ('val name = when (n) { ... }') is everyday Kotlin and only
// worked as a function body before.
assignexpr: expr
	|shortIf
	|whenStmt
	|lambdaexpression;
/* Each branch is an assignexpr, the same set every other value position takes: that
   is what makes 'if (a) 1 else if (b) 2 else 3' and a branch holding a pipeline parse.
   The optional else binds to the innermost if, as in Kotlin. */
shortIf:'if''(' condition ')' (assignexpr|retStmt) ('else' (assignexpr|retStmt))?;

// 'return when {...}' and 'return if (a) x else y' are normal Kotlin. Without these
// alternatives the when parsed as a separate statement and the return came out empty.
/* The returned value is an assignexpr, the same set every other value position takes.
   With only 'expr|shortIf|whenStmt' here, 'return xs.filter { ... }' matched the bare
   'return' and the pipeline was dropped without a word. */
retStmt:'return' assignexpr?;

expression:num
	|listID
	|chain
	|call
	|stringLiteral
	|exprID
	// A whole expr, not just an arithmetic one: '(a && b) || c' and '!(a < b)' are
	// everyday Kotlin, and with 'expression' in here no boolean parenthesis parsed.
	|'(' expr ')'
	|'-' expression
	|expression ('*'|'/'|'%')expression
	|expression('+'|'-')expression
	|exprID('++'|'--');

// A member path of any length, so 'a.b().c()', '"42".toIntOrNull()' and the safe
// call 's?.length' all parse. The older exprID/call rules still cover the one-hop
// forms; this rule takes over as soon as there is more than one hop, a call in the
// middle, or a '?.'.
chain: chainStart chainLink+;
chainStart: callHead|ID|stringLiteral;
callHead: callName'('argList?')';
chainLink: ('.'|'?.') memberName ('('argList?')')?;

/* Soft keywords: Kotlin lets all of these be ordinary identifiers, but spelling them
   in the rules above turned them into reserved tokens, so 'list.get(0)' or a method
   named 'open' stopped parsing. This rule is used everywhere a name is expected. */
memberName: ID|'get'|'step'|'downTo'|'open'|'internal'|'const';
exprID:ID|ID'.'memberName;
listID:ID'['(ID|INT)']';
// '===' and '!==' are Kotlin's reference comparison, which is what Java's '==' means.
logexpression: logexpression('>'|'<'|'!=='|'!='|'==='|'=='|'>='|'<=')logexpression
	|logexpression('&&'|'||')logexpression
	|logsides;
	

/* The trailing part must be an 'is'/'in' test, not any logsides: allowing that let
   two operands sit side by side with no operator, so 'val same = p == q' followed by
   a statement glued them together and the statement silently disappeared. */
logsides:'!'logsides
	|'is' type
	|'in' (explicitRange|exprID)
	|(expression|stringLiteral) logsuffix?;
logsuffix:'!'? ('is' type|'in' (explicitRange|exprID));

call: callName'(' argList? ')'| ID'.'memberName'(' argList? ')';

callName: memberName|'Any';
// ID at the end so a class declared in the file can be used as a type, which the
// closed list of built-in names made impossible ('var engine: Engine').
type:'Int' | 'Unit' | 'Long' | 'String' | 'Double' | 'Boolean'|'Byte'|'Any'|ID;

/* A single entry point. With five alternatives, one of them could match just a
   prefix and leave the rest of the input to be read as another statement: with
   'stringLiteral' first, '"a$b" is String' parsed the string and dropped the test.
   logexpression reaches every other form, so nothing is lost. */
expr: logexpression;

/* A trailing comma is allowed, as in modern Kotlin. Each argument is an assignexpr,
   the same set a value position accepts anywhere else: 'println(if (c) 1 else 2)' and
   'println(words.filter { ... })' are ordinary Kotlin and only worked when the value
   was assigned to a variable first. */
argList: assignexpr (',' assignexpr)* ','?;

// The whole string is a single lexer token: that keeps its exact content
// (spacing included) and lets the visitor expand the '$id' / '${expr}'
// templates afterwards. This used to be a parser rule, so the content was
// re-tokenized, which destroyed the spacing and dropped the interpolation.
stringLiteral: STRING;
num:INT|REAL;


//Lexer rules
// The numeric suffix lives here rather than in a parser rule: as a rule, the
// 'L'/'f'/'F' literals became implicit tokens with priority over ID, so an
// identifier named f, F or L failed to parse ("fun f()" did not).
STRING: '"' ( '\\' . | ~["\\\r\n] )* '"';
// No sign in the literals: with '-' inside INT the lexer read 'a-1' as 'a' and
// '-1', which parsed as two statements. The minus is a unary operator above.
/* Hexadecimal, binary and underscore-separated literals, all of which Kotlin has and
   Java accepts verbatim. Without them '0x1F' lexed as '0', 'x1' and 'F', which parsed
   as three separate statements and silently changed the value. */
INT:'0'[xX][0-9a-fA-F][0-9a-fA-F_]*[lL]?
   |'0'[bB][01][01_]*[lL]?
   |[0-9][0-9_]*[lL]?;
/* The exponent form too, with or without a dot: '1e10' used to lex as '1' followed by
   the name 'e10', which left the value as 1 and only warned about the leftover. */
REAL:[0-9][0-9_]*'.'[0-9][0-9_]*EXPONENT?[fFdD]?
    |[0-9][0-9_]*EXPONENT[fFdD]?;
fragment EXPONENT:[eE][+-]?[0-9]+;
/* A Kotlin name: letters, digits and underscores, never starting with a digit.
   The old rule ([A-Za-z]+[0-9]*) rejected every 'MAX_SIZE' and 'my_name', and read
   'v1a' as three tokens. */
ID: [A-Za-z_][A-Za-z_0-9]*;
// Kotlin's semicolons are optional separators; dropping them leaves the statement
// rules unchanged and lets 'val a = 1; val b = 2' parse.
SEMICOLON: ';' -> skip;
// Editors on Windows leave a byte order mark at the start of the file; Kotlin and
// javac both ignore it, and without this rule it failed as an unrecognised token.
BOM: '\uFEFF' -> skip;
WHITESPACE:  [ \t]+ -> skip;
NEWLINE : ('\r'?'\n'|'\r') -> skip;
COMMENT: '//' ~[\r\n]* -> skip;
MULTILINE_COMMENT: '/*' .*? '*/' -> skip;
