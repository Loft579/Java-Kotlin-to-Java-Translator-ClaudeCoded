import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

/* Kotlin -> Java translator.
 *
 * Two passes: collect() records every declaration (classes, functions, globals)
 * so forward references resolve, then the emit methods build the Java source.
 *
 * Two rules keep this honest, and both were what the previous implementation
 * lacked:
 *   - every method RETURNS the code it generates; there is no shared token list
 *     that later stages patch by index,
 *   - types are answered on demand by typeOf(), instead of being guessed from
 *     whatever token happened to be emitted last.
 */

/* ANTLR reports an unknown character as a LEXER error, and Parser.getNumberOfSyntaxErrors
   does not count those: an operator the grammar lacks (such as '%') was dropped from the
   token stream and the translation carried on silently. This counts both kinds. */
class Problems extends BaseErrorListener {
	int count;

	@Override public void syntaxError(Recognizer<?,?> recognizer,Object offending,int line,
			int pos,String msg,RecognitionException e){
		count++;
		System.err.println("line "+line+":"+pos+" "+msg);
	}

	void attach(Lexer lexer,Parser parser){
		lexer.removeErrorListeners();
		lexer.addErrorListener(this);
		parser.removeErrorListeners();
		parser.addErrorListener(this);
	}
}

/** A declared function: top level, class member, or a hoisted local function. */
class Fn {
	String name;
	final List<String> paramNames = new ArrayList<String>();
	final List<String> paramTypes = new ArrayList<String>();
	String declaredReturn;                    // null => infer from the body
	KotlinParser.FunctionContext body;
	boolean isOverride;
	boolean isLocal;                          // declared inside another function
	String access="public";                   // Java access level to emit
	String home;                              // generated class hosting it, null = this file
	/* Declarations sharing this name, this one included. A table keyed by name alone
	   cannot tell two overloads apart, so the rest hang off the first. */
	final List<Fn> overloads = new ArrayList<Fn>();
	/* Variables of the enclosing function that the body reads. Java has no local
	   functions, so they travel as extra parameters (lambda lifting). */
	final LinkedHashMap<String,String> captured = new LinkedHashMap<String,String>();
}

/* A when read once: its subject and its branches. Both forms of when -- the
   statement and the value -- are built from this, so a change in how branches are
   read cannot reach one form and miss the other. */
class WhenParts {
	String subject;                                         // translated, or null
	String subjectType;
	String prelude;                                         // local for a call subject
	boolean failed;
	final List<String> tests = new ArrayList<String>();      // one per non-else branch
	final List<ParseTree> bodies = new ArrayList<ParseTree>();
	final List<String> casts = new ArrayList<String>();       // narrowing per branch
	ParseTree elseBody;
}

/** A declared class or interface. */
class Cls {
	String name;
	boolean isInterface;
	boolean isAbstract;
	String superName;
	final List<String> interfaces = new ArrayList<String>();
	final LinkedHashMap<String,String> ctorParams = new LinkedHashMap<String,String>();
	final LinkedHashMap<String,String> fields = new LinkedHashMap<String,String>();
	final Set<String> finalFields = new LinkedHashSet<String>();
	final Map<String,String> fieldAccess = new HashMap<String,String>();
	/* Properties declared in the body rather than as constructor parameters. They are
	   emitted by the statement path, so they are kept apart from 'fields', which the
	   constructor assigns, but they still have to answer type lookups. */
	final Map<String,String> bodyFields = new LinkedHashMap<String,String>();
	final LinkedHashMap<String,String> getters = new LinkedHashMap<String,String>();
	// the ones written with a body, which an interface emits as a default method
	final Map<String,KotlinParser.GetterContext> getterBodies =
		new LinkedHashMap<String,KotlinParser.GetterContext>();
	final LinkedHashMap<String,Fn> methods = new LinkedHashMap<String,Fn>();
	KotlinParser.ClassDeclContext ctx;
	String home;                              // generated class hosting it, null = this file
}

class Translator {
	final String mainClass;
	final File baseDir;                                        // for resolving imports
	final Set<String> visitedDeps;              // shared: the ones being translated now
	/* Translating a dependency once is right; adopting it once is not. Two files can
	   import the same third one, and each of them needs its declarations. */
	final Map<String,Translator> depCache;
	final Map<String,String> globalHome=new LinkedHashMap<String,String>();
	final List<String> notes=new ArrayList<String>();           // what to translate next
	final Map<String,String> depSources=new LinkedHashMap<String,String>();
	boolean emitDeps;                                           // --deps: also write them
	String packageName;
	final Map<String,Cls> classes = new LinkedHashMap<String,Cls>();
	final Map<String,Fn> functions = new LinkedHashMap<String,Fn>();
	final Map<String,String> globals = new LinkedHashMap<String,String>();
	/* Written as the first line of every generated file. It is what tells a file this
	   tool produced from one somebody wrote by hand, so a dependency is never
	   overwritten without being sure. */
	static final String GENERATED_MARK="// generated by Kotlin2Java";
	String sourceName;                                        // the .kt it came from
	final List<String> header = new ArrayList<String>();      // package / imports
	final Deque<Map<String,String>> scopes = new ArrayDeque<Map<String,String>>();
	/* Where the current function's scopes begin. Frames below it belong to the class
	   or to the file, and a local may shadow those in Java; only shadowing inside the
	   function itself is an error. */
	int scopeBase;
	final List<String> hoisted = new ArrayList<String>();      // local funs lifted out
	/* Declarations whose return type is being inferred right now. Held by identity:
	   keyed by name, one overload would block another. */
	final Set<Fn> inferring = new HashSet<Fn>();
	boolean needsToIntOrNull;                                  // emit the helper below
	boolean needsRegex;                                        // import java.util.regex
	boolean needsFind;                                         // emit findOrNull
	boolean needsMatchEntire;                                  // emit regexMatchEntire
	boolean needsFindAll;                                      // emit regexFindAll
	boolean needsCollectors;                                   // import java.util.stream
	boolean needsLast;                                         // emit lastOf
	boolean needsJoin;                                         // emit joinToString
	boolean inConstructor;                                     // init block: fields need 'this.'
	int temps;                                                  // names the translator mints
	/* Kotlin narrows a variable's type inside 'if (x is T)'; Java needs an explicit
	   cast at every use, so the narrowing is recorded while that branch is emitted. */
	final Map<String,String> smartCasts=new HashMap<String,String>();
	/* Untranslatable constructs. A set: the same construct is visited once for its
	   code and once for its type, and one diagnosis per problem is what helps. */
	final Set<String> errors = new LinkedHashSet<String>();
	Cls current;
	Cls ownClass;                                              // class named after the file
	/* Properties whose type comes from their initializer. The initializer may mention
	   declarations that this pass has not reached yet, so they are resolved once the
	   whole file is known. */
	final List<Object[]> pendingProperties=new ArrayList<Object[]>();
	/* File properties wait too: the type of 'val GREETING = build()' is only known once
	   build() has been collected, which may happen further down the file. */
	final List<KotlinParser.WithassignContext> pendingGlobals=
		new ArrayList<KotlinParser.WithassignContext>();

	Translator(String mainClass,File baseDir,Set<String> visitedDeps){
		this(mainClass,baseDir,visitedDeps,new HashMap<String,Translator>());
	}

	Translator(String mainClass,File baseDir,Set<String> visitedDeps,
			Map<String,Translator> depCache){
		this.mainClass=mainClass;
		this.baseDir=baseDir;
		this.visitedDeps=visitedDeps;
		this.depCache=depCache;
	}

	/** Qualified name of a declaration: 'Point' locally, 'Utils.Point' if imported. */
	static String javaName(Cls k){ return k.home==null?k.name:k.home+"."+k.name; }

	/** The class behind a possibly qualified type name. */
	Cls classOf(String type){
		if(type==null)return null;
		int dot=type.lastIndexOf('.');
		return classes.get(dot<0?type:type.substring(dot+1));
	}

	// ---------------------------------------------------------------- types

	static final Map<String,String> PRIMITIVE = new HashMap<String,String>();
	static final Map<String,String> BOXED = new HashMap<String,String>();
	static {
		PRIMITIVE.put("Int","int");
		PRIMITIVE.put("Long","long");
		PRIMITIVE.put("Double","double");
		PRIMITIVE.put("Float","float");
		PRIMITIVE.put("Boolean","boolean");
		PRIMITIVE.put("Byte","byte");
		PRIMITIVE.put("Char","char");
		PRIMITIVE.put("Unit","void");
		PRIMITIVE.put("String","String");
		PRIMITIVE.put("Any","Object");
		BOXED.put("int","Integer");
		BOXED.put("long","Long");
		BOXED.put("double","Double");
		BOXED.put("float","Float");
		BOXED.put("boolean","Boolean");
		BOXED.put("byte","Byte");
		BOXED.put("char","Character");
	}

	static String box(String t){
		if(t==null)return null;
		String b=BOXED.get(t);
		return b!=null?b:t;
	}

	/** True for types compared by reference in Java (everything except primitives). */
	static boolean isReference(String t){
		return t!=null&&!BOXED.containsKey(t)&&!t.equals("void");
	}

	/* A Kotlin MatchResult is carried as the list of its group values, index 0 being
	   the whole match. The name below is internal: emitType turns it into the Java
	   type, which keeps '.value' and '.groupValues' from applying to every list. */
	static final String MATCH="MatchResult";

	/* Types are qualified where they are created (typeName) or adopted (adoptType), so
	   nothing here has to look a class up: only the internal MatchResult needs a name
	   Java knows. */
	String emitType(String t){
		if(MATCH.equals(t))return "List<String>";
		return t;
	}

	/* The one place a written Kotlin type becomes a Java one. It qualifies a class
	   that came from a dependency here, at birth, so none of the places that later
	   print a type has to remember to do it. */
	String typeName(String kotlin,boolean nullable){
		String t=PRIMITIVE.get(kotlin);
		if(t==null){
			Cls k=classes.get(kotlin);
			t=k==null?kotlin:javaName(k);
		}
		return nullable?box(t):t;
	}

	/** Element type of a List<T>/Set<T>, or null. */
	static String elem(String t){
		if(t==null)return null;
		int lt=t.indexOf('<');
		int gt=t.lastIndexOf('>');
		return (lt>0 && gt>lt)?t.substring(lt+1,gt):null;
	}

	static int rank(String t){
		if(t==null)return 0;
		if(t.equals("String"))return 9;
		if(t.equals("double")||t.equals("Double"))return 4;
		if(t.equals("float")||t.equals("Float"))return 3;
		if(t.equals("long")||t.equals("Long"))return 2;
		if(t.equals("int")||t.equals("Integer")||t.equals("byte")||t.equals("Byte"))return 1;
		return 0;
	}

	/** Result type of an arithmetic operator. */
	static String promote(String a,String b){
		if("String".equals(a)||"String".equals(b))return "String";
		return rank(a)>=rank(b)?a:b;
	}

	// ---------------------------------------------------------------- scopes

	void push(){ scopes.push(new HashMap<String,String>()); }
	void pop(){ scopes.pop(); }
	void declare(String name,String type){
		checkJavaName(name,"name");
		/* Kotlin lets an inner declaration shadow a parameter or an outer local; Java
		   does not, and the error it gives is confusing, so it is reported here. Fields
		   and file properties are left out: shadowing those IS legal in Java. */
		int withinFunction=scopes.size()-scopeBase;
		int depth=0;
		for(Map<String,String> frame:scopes){
			if(depth++>=withinFunction)break;
			if(frame.containsKey(name)){
				errors.add("'"+name+"' shadows a parameter or an enclosing local;"
					+" Java does not allow that, rename it");
				break;
			}
		}
		scopes.peek().put(name,type);
	}

	String lookup(String name){
		if(smartCasts.containsKey(name))return smartCasts.get(name);
		/* 'this' and 'super' are receivers like any other, and knowing their type is
		   what lets a property backed by a getter be read as getX() rather than as a
		   field that does not exist. */
		if(current!=null&&name.equals("this"))return javaName(current);
		if(current!=null&&name.equals("super")&&current.superName!=null)
			return current.superName;
		for(Map<String,String> s:scopes)if(s.containsKey(name))return s.get(name);
		if(current!=null){
			String t=memberType(current,name);
			if(t!=null)return t;
		}
		return globals.get(name);
	}

	/* Members can come from a superclass or an implemented interface, so every
	   lookup walks the chain instead of only checking the class itself. */
	/* A class, the interfaces it implements and its ancestors, in that order and each
	   one once. The visited set is what keeps a malformed 'class A : A()' from
	   spinning forever: three different lookups walk this chain. */
	List<Cls> hierarchy(Cls k){
		List<Cls> chain=new ArrayList<Cls>();
		Set<String> seen=new HashSet<String>();
		Deque<Cls> pending=new ArrayDeque<Cls>();
		if(k!=null)pending.add(k);
		while(!pending.isEmpty()){
			Cls next=pending.poll();
			if(next==null||!seen.add(next.name))continue;
			chain.add(next);
			for(String i:next.interfaces){
				Cls face=classOf(i);
				if(face!=null)pending.add(face);
			}
			Cls parent=classOf(next.superName);
			if(parent!=null)pending.add(parent);
		}
		return chain;
	}

	/* The class in the chain that declares this member. Properties and methods are
	   separate namespaces in Kotlin, so each is searched on its own: a method 'size()'
	   in a subclass used to be found first and then answer nothing when asked for the
	   type of the PROPERTY 'size' inherited from above, which read it as a field. */
	Cls ownerOfProperty(Cls k,String member){
		for(Cls step:hierarchy(k))
			if(step.getters.containsKey(member)||step.fields.containsKey(member)
					||step.bodyFields.containsKey(member))return step;
		return null;
	}

	Cls ownerOfMethod(Cls k,String member){
		for(Cls step:hierarchy(k))
			if(step.methods.containsKey(member))return step;
		return null;
	}

	String memberType(Cls k,String member){
		Cls owner=ownerOfProperty(k,member);
		if(owner==null)return null;
		if(owner.getters.containsKey(member))return owner.getters.get(member);
		if(owner.fields.containsKey(member))return owner.fields.get(member);
		return owner.bodyFields.get(member);
	}

	boolean isGetter(Cls k,String member){
		Cls owner=ownerOfProperty(k,member);
		return owner!=null&&owner.getters.containsKey(member);
	}

	Fn methodOf(Cls k,String member){
		Cls owner=ownerOfMethod(k,member);
		return owner==null?null:owner.methods.get(member);
	}

	static void warn(String msg){ System.err.println("warning: "+msg); }

	/** Records a declaration, keeping any earlier one with the same name. */
	static void register(Map<String,Fn> table,Fn fn){
		Fn entry=table.get(fn.name);
		if(entry==null){
			fn.overloads.add(fn);
			table.put(fn.name,fn);
			return;
		}
		entry.overloads.add(fn);
	}

	/* Every declaration in a table: the entries plus the overloads hanging off them.
	   Iterating values() alone visits only the first declaration of each name. */
	static List<Fn> allDeclarations(Map<String,Fn> table){
		List<Fn> all=new ArrayList<Fn>();
		for(Fn entry:table.values()){
			if(entry.overloads.isEmpty())all.add(entry);
			else all.addAll(entry.overloads);
		}
		return all;
	}

	/** The declaration a call of this shape refers to. */
	static Fn pick(Fn entry,int argCount){
		if(entry==null)return null;
		for(Fn candidate:entry.overloads)
			if(candidate.paramNames.size()==argCount)return candidate;
		return entry;
	}

	/* Which overload a call means. The count of arguments narrows it, and when several
	   overloads share that count only their types can tell them apart -- taking the
	   first one made 'f("x")' answer the return type of 'f(n: Int)'. */
	Fn pickFor(Fn entry,List<String> argTypes){
		if(entry==null)return null;
		List<Fn> sameArity=new ArrayList<Fn>();
		for(Fn candidate:entry.overloads)
			if(candidate.paramNames.size()==argTypes.size())sameArity.add(candidate);
		if(sameArity.isEmpty())return entry;
		for(Fn candidate:sameArity)if(fitsParams(candidate,argTypes))return candidate;
		return sameArity.get(0);
	}

	/** True when every argument whose type is known can be that parameter. */
	boolean fitsParams(Fn fn,List<String> argTypes){
		for(int i=0;i<argTypes.size();i++){
			String want=fn.paramTypes.get(i);
			String got=argTypes.get(i);
			if(want==null||got==null)continue;             // nothing known, no evidence
			if(box(want).equals(box(got)))continue;
			// a number goes into any numeric parameter, as Java widens it
			if(rank(want)>0&&rank(got)>0
					&&!"String".equals(want)&&!"String".equals(got))continue;
			if(assignableTo(got,want))continue;
			return false;
		}
		return true;
	}

	/** The declaration that this piece of source is. */
	static Fn declarationFor(Fn entry,KotlinParser.FunctionContext ctx){
		if(entry==null)return null;
		for(Fn candidate:entry.overloads)
			if(candidate.body==ctx)return candidate;
		return entry.body==ctx?entry:null;
	}

	/* One policy for everything the translator cannot express: stop. Substituting
	   something plausible -- the source text, a constant -- is how a translation
	   changes meaning without anyone noticing. The value returned here never reaches
	   a file, because a recorded error suppresses the output. */
	String cannotTranslate(String what){
		errors.add(what);
		return "null";
	}

	/* Names that Kotlin allows but Java reserves. Emitting them would produce code
	   that cannot compile, so they are reported against the Kotlin source. */
	/* '_' is on the list because Java 9 reserved it: a Kotlin '_' (an unused lambda
	   parameter, say) cannot keep that name on the other side. */
	static final Set<String> JAVA_KEYWORDS=new HashSet<String>(Arrays.asList("_",
		"abstract","assert","boolean","byte","case","catch","char","const","default",
		"double","enum","extends","final","float","goto","implements","instanceof","int",
		"long","native","new","private","protected","public","short","static","strictfp",
		"switch","synchronized","throws","transient","void","volatile"));

	void checkJavaName(String name,String what){
		if(JAVA_KEYWORDS.contains(name))
			errors.add(what+" '"+name+"' is a Java keyword; rename it in the Kotlin source");
	}

	// ------------------------------------------------------------ pass 1

	void collect(KotlinParser.ProgContext prog){
		for(ParseTree child:children(prog)){
			if(child instanceof KotlinParser.PackageListContext)collectHeader((ParserRuleContext)child);
			else scanDecls(child);
		}
		resolvePendingProperties();
	}

	void collectHeader(ParserRuleContext ctx){
		for(ParseTree p:children(ctx)){
			ParserRuleContext item=(ParserRuleContext)((ParserRuleContext)p).getChild(0);
			StringBuilder text=new StringBuilder();
			for(int i=1;i<item.getChildCount();i++)text.append(item.getChild(i).getText());
			String name=text.toString();
			if(!(item instanceof KotlinParser.ImportPContext)){
				packageName=name;
				header.add("package "+name+";");
				continue;
			}
			/* The Kotlin stdlib is mapped, not imported: it has no Java counterpart.
			   Matched as a package, not as a prefix: 'kotlinutils.helpers' is somebody's
			   own code and has to be resolved on disk like any other import. */
			if(name.equals("kotlin")||name.startsWith("kotlin."))continue;
			if(name.startsWith("java.")||name.startsWith("javax.")){
				header.add("import "+name+";");
				continue;
			}
			File dep=resolveDependency(name);
			if(dep!=null){
				loadDependency(dep);
				continue;
			}
			warn("import '"+name+"' resolves to no .kt or .java file near the source; dropped");
		}
	}

	// -------------------------------------------------------- dependencies

	/* An import that is neither stdlib nor java.* is assumed to name Kotlin source
	   of this project. The dotted path is tried as a file path, dropping leading
	   package segments and an optional trailing declaration name, so both
	   'import demo.utils' and 'import demo.utils.double' find demo/utils.kt. */
	File resolveDependency(String dotted){
		if(baseDir==null)return null;
		String path=dotted.endsWith(".*")?dotted.substring(0,dotted.length()-2):dotted;
		String[] parts=path.split("\\.");
		for(File root:searchRoots())
			for(int drop=0;drop<2;drop++){          // the last segment may name a declaration
				int end=parts.length-drop;
				if(end<1)break;
				for(int start=0;start<end;start++){  // ... and the first ones the package
					StringBuilder rel=new StringBuilder();
					for(int i=start;i<end;i++){
						if(rel.length()>0)rel.append(File.separator);
						rel.append(parts[i]);
					}
					// Kotlin source first: it can be indexed. An already translated .java
					// is accepted too, so a dependency only has to be translated once --
					// under its own name or under the capitalised one this tool gives it.
					File kt=new File(root,rel+".kt");
					if(kt.isFile())return kt;
					for(String candidate:new String[]{rel.toString(),capitaliseLast(rel.toString())}){
						File java=new File(root,candidate+".java");
						if(java.isFile())return java;
					}
				}
			}
		return null;
	}

	/** 'demo/utils' -> 'demo/Utils', the name this tool gives a translated file. */
	static String capitaliseLast(String path){
		int cut=path.lastIndexOf(File.separatorChar);
		String dir=cut<0?"":path.substring(0,cut+1);
		String name=path.substring(cut+1);
		return name.isEmpty()?path:dir+Character.toUpperCase(name.charAt(0))+name.substring(1);
	}

	/* Where to look: the directory of the file, and the source root implied by its
	   package, so 'import demo.utils' inside src/demo/app.kt finds src/demo/utils.kt
	   by the path the import actually states. */
	List<File> searchRoots(){
		List<File> roots=new ArrayList<File>();
		roots.add(baseDir);
		if(packageName==null)return roots;
		File root=baseDir;
		String[] pkg=packageName.split("\\.");
		for(int i=pkg.length-1;i>=0&&root!=null;i--){
			if(!root.getName().equals(pkg[i]))break;
			root=root.getParentFile();
		}
		if(root!=null&&!root.equals(baseDir))roots.add(root);
		return roots;
	}

	void loadDependency(File f){
		String key;
		try{
			key=f.getCanonicalPath();
		}catch(IOException e){
			key=f.getAbsolutePath();
		}
		String cls=dependencyClass(f);
		if(f.getName().endsWith(".java")){
			String pkg=javaPackageOf(f);
			if(pkg!=null&&!pkg.equals(packageName))header.add("import "+pkg+"."+cls+";");
			indexJavaDependency(f,cls);
			notes.add("dependency "+f.getName()+" is already Java; compile it alongside"
				+" the output");
			return;
		}
		Translator cached=depCache.get(key);
		if(cached!=null){                                // translated by another importer
			adopt(cached,cached.mainClass);
			return;
		}
		/* Two files with the same name would be translated to the same .java, and the
		   second would quietly overwrite the first. */
		for(Map.Entry<String,Translator> other:depCache.entrySet())
			if(other.getValue().mainClass.equals(cls)){
				errors.add("dependencies '"+other.getKey()+"' and '"+f.getPath()
					+"' would both be translated to "+cls+".java; rename one of them");
				return;
			}
		/* Already being translated further up the chain: its declarations are not
		   collected yet, so every reference to them would come out unqualified. Cutting
		   the loop silently produced Java that does not compile, and the error landed on
		   the generated file rather than on the import that caused it. */
		if(!visitedDeps.add(key)){
			errors.add("'"+f.getName()+"' and the file that imports it import each other;"
				+" move what they share into a third file");
			return;
		}
		try{
			KotlinLexer lexer=new KotlinLexer(CharStreams.fromPath(f.toPath()));
			KotlinParser parser=new KotlinParser(new CommonTokenStream(lexer));
			Problems problems=new Problems();
			problems.attach(lexer,parser);
			KotlinParser.ProgContext tree=parser.prog();
			if(problems.count>0){
				errors.add("dependency '"+f.getName()+"' has "+problems.count
					+" syntax error(s)");
				return;
			}
			/* Only its declarations are read, which is what qualifying the references
			   needs. Translating the dependency itself is left to the user, run next
			   to the source it came from. */
			Translator dep=new Translator(cls,f.getParentFile(),visitedDeps,depCache);
			dep.emitDeps=emitDeps;
			dep.sourceName=f.getName();
			dep.collect(tree);
			if(emitDeps){
				String java=dep.translate(tree);
				depSources.putAll(dep.depSources);           // transitive first
				depSources.put(cls,java);
			}
			depCache.put(key,dep);
			errors.addAll(dep.errors);
			notes.addAll(dep.notes);
			notes.add(emitDeps
				?"dependency "+f.getName()+" translated as "+cls+".java"
				:"dependency "+f.getName()+" is used; translate it with:  java Kotlin2Java "
					+f.getPath()+" "+cls+".java   (or pass --deps)");
			adopt(dep,cls);
		}catch(IOException e){
			errors.add("cannot read dependency "+f+": "+e.getMessage());
		}finally{
			// done, so no longer part of any cycle; depCache is what keeps it from
			// being translated a second time
			visitedDeps.remove(key);
		}
	}

	/* Brings a dependency's declarations into scope, tagged with the generated
	   class that hosts them so every reference gets qualified. */
	/* A dependency that is already Java cannot be parsed by this grammar, but its
	   visible static declarations can be read off the source, which is all that
	   qualifying the references needs. Enough for files this tool generated. */
	void indexJavaDependency(File f,String cls){
		java.util.regex.Pattern clazz=java.util.regex.Pattern.compile(
			"^(?!private)(?:public\\s+|protected\\s+)?(?:static\\s+)?(?:abstract\\s+)?"
			+"(?:class|interface)\\s+(\\w+)");
		java.util.regex.Pattern method=java.util.regex.Pattern.compile(
			"^(?!private)(?:public\\s+|protected\\s+)?static\\s+(?:final\\s+)?"
			+"([\\w.]+(?:<[^>]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*\\(");
		java.util.regex.Pattern field=java.util.regex.Pattern.compile(
			"^(?!private)(?:public\\s+|protected\\s+)?static\\s+(?:final\\s+)?"
			+"([\\w.]+(?:<[^>]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*[=;]");
		try(BufferedReader in=new BufferedReader(new InputStreamReader(
				new FileInputStream(f),StandardCharsets.UTF_8))){
			String line;
			while((line=in.readLine())!=null){
				String t=line.trim();
				java.util.regex.Matcher m=clazz.matcher(t);
				if(m.find()){
					String name=m.group(1);
					if(name.equals(cls))continue;             // the wrapper class itself
					Cls k=new Cls();
					k.name=name;
					k.home=cls;
					if(!classes.containsKey(name))classes.put(name,k);
					continue;
				}
				m=method.matcher(t);
				if(m.find()){
					Fn fn=new Fn();
					fn.name=m.group(2);
					fn.declaredReturn=m.group(1);
					fn.home=cls;
					register(functions,fn);
					continue;
				}
				m=field.matcher(t);
				if(m.find()){
					String name=m.group(2);
					if(!globals.containsKey(name)){
						globals.put(name,m.group(1));
						globalHome.put(name,cls);
					}
				}
			}
		}catch(IOException e){
			errors.add("cannot read dependency "+f+": "+e.getMessage());
		}
	}

	/** The package declared by an existing .java file, if any. */
	static String javaPackageOf(File f){
		try(BufferedReader in=new BufferedReader(new InputStreamReader(
				new FileInputStream(f),StandardCharsets.UTF_8))){
			String line;
			while((line=in.readLine())!=null){
				String t=line.trim();
				if(!t.startsWith("package "))continue;
				return t.substring(8).replace(";","").trim();
			}
		}catch(IOException e){
			// unreadable: treated as having no package
		}
		return null;
	}

	/** Generated class name of a dependency file: utils.kt -> Utils. */
	static String dependencyClass(File f){
		String cls=Kotlin2Java.className(f.getName());
		return Character.toUpperCase(cls.charAt(0))+cls.substring(1);
	}

	/* A type written inside a dependency names its classes plainly; seen from here
	   they live inside that file's class, so the name is rewritten once, on adoption. */
	String adoptType(Translator dep,String type){
		if(type==null)return null;
		String element=elem(type);
		if(element!=null)
			return type.substring(0,type.indexOf('<'))+"<"+adoptType(dep,element)+">";
		Cls k=dep.classes.get(type);
		return k==null?type:javaName(k);
	}

	void rewriteTypes(Translator dep,Map<String,String> types){
		for(Map.Entry<String,String> e:types.entrySet())
			e.setValue(adoptType(dep,e.getValue()));
	}

	/* Every place a declaration carries a type, listed once. Qualifying only some of
	   them is what left 'extends Figure' unqualified, so the list is exhaustive on
	   purpose: fields, properties, constructor parameters, both kinds of function
	   signature, the parents of a class, and the file's globals. */
	void qualifyDeclarations(Translator dep){
		for(Cls k:dep.classes.values()){
			rewriteTypes(dep,k.fields);
			rewriteTypes(dep,k.getters);
			rewriteTypes(dep,k.bodyFields);
			rewriteTypes(dep,k.ctorParams);
			k.superName=adoptType(dep,k.superName);
			for(int i=0;i<k.interfaces.size();i++)
				k.interfaces.set(i,adoptType(dep,k.interfaces.get(i)));
			for(Fn fn:allDeclarations(k.methods))qualifySignature(dep,fn);
		}
		for(Fn fn:allDeclarations(dep.functions))qualifySignature(dep,fn);
		for(Map.Entry<String,String> g:dep.globals.entrySet())
			g.setValue(adoptType(dep,g.getValue()));
	}

	void qualifySignature(Translator dep,Fn fn){
		fn.declaredReturn=adoptType(dep,fn.declaredReturn);
		for(int i=0;i<fn.paramTypes.size();i++)
			fn.paramTypes.set(i,adoptType(dep,fn.paramTypes.get(i)));
		for(Map.Entry<String,String> c:fn.captured.entrySet())
			c.setValue(adoptType(dep,c.getValue()));
	}

	/* Two dependencies offering the same name is not an error -- one of them is used --
	   but it has to be said, or a reference would quietly go to the wrong file. A
	   declaration of this file wins over any import and needs no warning, which is what
	   a null home means. */
	void reportShadowed(String kind,String name,String mine,String theirs){
		if(mine!=null&&!mine.equals(theirs))
			warn(kind+" '"+name+"' comes from both "+mine+" and "+theirs
				+"; "+mine+" is used");
	}

	void adopt(Translator dep,String cls){
		// classes first: the qualification below needs their home already set
		for(Cls k:dep.classes.values())
			if(k.home==null&&!k.name.equals(cls))k.home=cls;
		qualifyDeclarations(dep);
		// every declaration, not just the first of each name, keeps its own return type
		for(Fn fn:allDeclarations(dep.functions)){
			fn.declaredReturn=dep.returnTypeOf(fn);
			if(fn.home==null)fn.home=cls;
		}
		for(Map.Entry<String,Fn> e:dep.functions.entrySet()){
			Fn fn=e.getValue();
			Fn existing=functions.get(e.getKey());
			if(existing==null){
				functions.put(e.getKey(),fn);
				continue;
			}
			reportShadowed("function",e.getKey(),existing.home,fn.home);
		}
		for(Map.Entry<String,Cls> e:dep.classes.entrySet()){
			Cls existing=classes.get(e.getKey());
			if(existing==null){
				classes.put(e.getKey(),e.getValue());
				continue;
			}
			reportShadowed("class",e.getKey(),existing.home,e.getValue().home);
		}
		for(Map.Entry<String,String> e:dep.globals.entrySet()){
			String home=dep.globalHome.containsKey(e.getKey())
				?dep.globalHome.get(e.getKey()):cls;
			if(globals.containsKey(e.getKey())){
				reportShadowed("property",e.getKey(),globalHome.get(e.getKey()),home);
				continue;
			}
			globals.put(e.getKey(),e.getValue());
			globalHome.put(e.getKey(),home);
		}
		if(dep.packageName!=null&&!dep.packageName.equals(packageName))
			header.add("import "+dep.packageName+"."+cls+";");
		if(dep.needsRegex)needsRegex=true;
	}

	/** Walks the tree looking for declarations, at any depth. */
	void scanDecls(ParseTree node){
		if(node instanceof KotlinParser.ClassDeclContext){
			declareClass((KotlinParser.ClassDeclContext)node);
			return;
		}
		if(node instanceof KotlinParser.InterfaceDeclContext){
			declareInterface((KotlinParser.InterfaceDeclContext)node);
			return;
		}
		if(node instanceof KotlinParser.FunctionContext){
			Fn fn=signature((KotlinParser.FunctionContext)node);
			if(fn!=null){
				fn.isLocal=node.getParent() instanceof KotlinParser.StmtContext;
				Fn prior=functions.get(fn.name);
				/* Hoisting flattens local functions into the same namespace as the rest,
				   and a local one carries its captures, so sharing a name would append
				   them to calls that cannot see those variables. */
				if(prior!=null&&(fn.isLocal||prior.isLocal))
					errors.add("local function '"+fn.name+"' has the same name as another"
						+" declaration; rename it");
				register(functions,fn);
			}
			// local functions are hoisted, so keep scanning the body
		}
		if(node instanceof KotlinParser.WithassignContext && isTopLevel(node)){
			KotlinParser.WithassignContext w=(KotlinParser.WithassignContext)node;
			String name=declName(w);
			checkJavaName(name,"property");
			globals.put(name,declType(w));
			pendingGlobals.add(w);
		}
		for(ParseTree c:children(node))scanDecls(c);
	}

	static boolean isTopLevel(ParseTree node){
		ParseTree p=node.getParent();
		return p instanceof KotlinParser.GlobalDeclContext
			||p instanceof KotlinParser.DeclGlobalListContext;
	}

	void declareClass(KotlinParser.ClassDeclContext ctx){
		Cls k=new Cls();
		k.ctx=ctx;
		k.isAbstract=hasModifier(ctx,"abstract");
		k.name=ctx.ID().getText();
		checkJavaName(k.name,"class");
		if(ctx.classparamList()!=null){
			for(KotlinParser.ParamIDContext p:params(ctx.classparamList())){
				String pname=p.ID().getText();
				String ptype=paramType(p);
				checkJavaName(pname,"constructor parameter");
				k.ctorParams.put(pname,ptype);
				/* Only 'val'/'var' parameters become properties; a bare one stays just a
				   constructor parameter. The keyword is looked up by type because a
				   visibility modifier may sit in front of it. */
				ParserRuleContext holder=(ParserRuleContext)p.getParent();
				KotlinParser.KeywordContext keyword=null;
				for(ParseTree c:children(holder))
					if(c instanceof KotlinParser.KeywordContext)
						keyword=(KotlinParser.KeywordContext)c;
				if(keyword!=null){
					k.fields.put(pname,ptype);
					String access=accessOf(holder);
					if(!access.equals("public"))k.fieldAccess.put(pname,access);
					if(keyword.getText().equals("val"))k.finalFields.add(pname);
				}
			}
		}
		for(KotlinParser.ClassORinterfaceContext ci:parents(ctx.extendsORimplements())){
			// 'Shape(args)' carries a constructor call, so it is the superclass;
			// a bare name is an interface. Both are types, so both are qualified here.
			String parent=typeName(ci.getChild(0).getText(),false);
			if(ci.getChildCount()==1)k.interfaces.add(parent);
			else k.superName=parent;
		}
		classes.put(k.name,k);
		if(ctx.classcompound()==null)return;
		for(ParseTree m:memberNodes(ctx.classcompound())){
			if(m instanceof KotlinParser.GetterContext){
				KotlinParser.GetterContext g=(KotlinParser.GetterContext)m;
				// no keyword check: a getter is emitted as getX(), so the name itself
				// never reaches the Java source
				k.getters.put(getterName(g),getterType(g));
			}else if(m instanceof KotlinParser.FunctionContext){
				Fn fn=signature((KotlinParser.FunctionContext)m);
				if(fn!=null)register(k.methods,fn);
			}else{
				registerBodyProperty(k,m);
			}
		}
	}

	/** A 'val'/'var' written in the class body, with its declared or inferred type. */
	void registerBodyProperty(Cls k,ParseTree member){
		KotlinParser.NoassignContext noassign=null;
		KotlinParser.WithassignContext withassign=null;
		for(ParseTree c:descendants(member)){
			if(c instanceof KotlinParser.NoassignContext&&noassign==null)
				noassign=(KotlinParser.NoassignContext)c;
			if(c instanceof KotlinParser.WithassignContext&&withassign==null)
				withassign=(KotlinParser.WithassignContext)c;
		}
		if(noassign!=null){
			k.bodyFields.put(noassign.ID().getText(),noassignType(noassign));
			return;
		}
		if(withassign!=null)pendingProperties.add(new Object[]{k,withassign});
	}

	/** Resolves the deferred property types, with the whole file now collected. */
	void resolvePendingProperties(){
		Cls outer=current;
		for(Object[] pending:pendingProperties){
			Cls k=(Cls)pending[0];
			KotlinParser.WithassignContext w=(KotlinParser.WithassignContext)pending[1];
			current=k;
			String type=typeOf(w.assignexpr());
			if(type!=null)k.bodyFields.put(declName(w),type);
		}
		current=outer;
		pendingProperties.clear();
		// a file property is resolved outside any class, hence after 'current' is back
		for(KotlinParser.WithassignContext w:pendingGlobals)
			globals.put(declName(w),declType(w));
		pendingGlobals.clear();
	}

	static List<ParseTree> descendants(ParseTree node){
		List<ParseTree> out=new ArrayList<ParseTree>();
		for(ParseTree c:children(node)){
			out.add(c);
			out.addAll(descendants(c));
		}
		return out;
	}

	void declareInterface(KotlinParser.InterfaceDeclContext ctx){
		Cls k=new Cls();
		k.isInterface=true;
		k.name=ctx.ID().getText();
		// an interface may extend other interfaces
		for(KotlinParser.ClassORinterfaceContext ci:parents(ctx.extendsORimplements()))
			k.interfaces.add(typeName(ci.getChild(0).getText(),false));
		for(ParseTree t:children(ctx)){
			if(t instanceof KotlinParser.GetterContext){
				KotlinParser.GetterContext prop=(KotlinParser.GetterContext)t;
				k.getters.put(getterName(prop),getterType(prop));
				k.getterBodies.put(getterName(prop),prop);
			}else if(t instanceof KotlinParser.DeclIDContext){
				KotlinParser.NoassignContext n=findNoassign((ParserRuleContext)t);
				if(n!=null)k.getters.put(n.ID().getText(),noassignType(n));
			}else if(t instanceof KotlinParser.FunctionContext){
				Fn fn=signature((KotlinParser.FunctionContext)t);
				if(fn!=null)register(k.methods,fn);
			}else if(t instanceof KotlinParser.FuncSignatureContext){
				Fn fn=signatureOnly((KotlinParser.FuncSignatureContext)t);
				register(k.methods,fn);
			}
		}
		classes.put(k.name,k);
	}

	/** Reads a function signature without translating its body. */
	Fn signature(KotlinParser.FunctionContext ctx){
		Fn fn=new Fn();
		if(ctx.abstractFunction()!=null){
			KotlinParser.AbstractFunctionContext a=ctx.abstractFunction();
			fn.name=a.memberName().getText();
			if(a.paramList()!=null)readParams(fn,a.paramList());
			fn.declaredReturn=funcTypeOf(a.funcType());
			return fn;
		}
		fn.isOverride=hasModifier(ctx,"override");
		fn.access=accessOf(ctx);
		fn.name=ctx.memberName().getText();
		checkJavaName(fn.name,"function");
		fn.body=ctx;
		if(ctx.paramList()!=null)readParams(fn,ctx.paramList());
		fn.declaredReturn=funcTypeOf(ctx.funcType());
		return fn;
	}

	/** A body-less declaration inside an interface. */
	Fn signatureOnly(KotlinParser.FuncSignatureContext ctx){
		Fn fn=new Fn();
		fn.access=accessOf(ctx);
		fn.name=ctx.memberName().getText();
		checkJavaName(fn.name,"function");
		if(ctx.paramList()!=null)readParams(fn,ctx.paramList());
		fn.declaredReturn=funcTypeOf(ctx.funcType());
		return fn;
	}

	void readParams(Fn fn,KotlinParser.ParamListContext list){
		for(KotlinParser.ParamIDContext p:params(list)){
			fn.paramNames.add(p.ID().getText());
			fn.paramTypes.add(paramType(p));
		}
	}

	/** funcType: ':' (type | ('List'|'Set') '<' type '>') ('?')? */
	String funcTypeOf(KotlinParser.FuncTypeContext ctx){
		if(ctx==null)return null;
		String kind=ctx.getChild(1).getText();
		if(kind.equals("List")||kind.equals("Set"))
			return kind+"<"+typeName(ctx.type().getText(),true)+">";
		boolean nullable=ctx.getChild(ctx.getChildCount()-1).getText().equals("?");
		return typeName(ctx.type().getText(),nullable);
	}

	/* A written type, in any of the shapes the grammar allows: a plain name, a
	   nullable one, or a List/Set of one. Only terminal children are inspected, so a
	   '?' inside a nested expression cannot be mistaken for the nullable marker. */
	String declaredType(ParserRuleContext ctx,KotlinParser.TypeContext type){
		boolean nullable=false;
		String collection=null;
		for(ParseTree c:children(ctx)){
			if(!(c instanceof TerminalNode))continue;
			String text=c.getText();
			if(text.equals("?"))nullable=true;
			else if(text.equals("List")||text.equals("Set"))collection=text;
		}
		if(collection!=null)return collection+"<"+typeName(type.getText(),true)+">";
		return typeName(type.getText(),nullable);
	}

	String paramType(KotlinParser.ParamIDContext p){
		return declaredType(p,p.type());
	}

	String noassignType(KotlinParser.NoassignContext n){
		return declaredType(n,n.type());
	}

	static String getterName(KotlinParser.GetterContext g){ return g.ID().getText(); }

	/** getter: ('override')? keyword ID (':' type | ('List'|'Set')'<'type'>') 'get' '(' ')' '=' expr */
	String getterType(KotlinParser.GetterContext g){
		return declaredType(g,g.type());
	}

	/** 'override' and 'open' both sit in front of a declaration. */
	static boolean isModifier(String word){
		return word.equals("override")||word.equals("open");
	}

	static final Set<String> MODIFIERS=new HashSet<String>(Arrays.asList(
		"private","protected","internal","public","open","abstract","override",
		"const","lateinit"));

	/* The words in front of a declaration, read once. A visibility is a rule of its
	   own while the rest are plain words, and reading them separately is what made
	   'private abstract class' lose its 'abstract'. */
	static List<String> modifiersOf(ParserRuleContext ctx){
		List<String> words=new ArrayList<String>();
		for(ParseTree c:children(ctx)){
			if(c instanceof KotlinParser.VisibilityContext){
				words.add(c.getText());
				continue;
			}
			if(!(c instanceof TerminalNode))break;          // past the modifiers
			if(!MODIFIERS.contains(c.getText()))break;
			words.add(c.getText());
		}
		return words;
	}

	/** Whether a visibility was written; absent one, a member needs no modifier. */
	static boolean hasVisibility(ParserRuleContext ctx){
		for(String word:modifiersOf(ctx))
			if(word.equals("private")||word.equals("protected")||word.equals("internal")
					||word.equals("public"))return true;
		return false;
	}

	static boolean hasModifier(ParserRuleContext ctx,String word){
		return modifiersOf(ctx).contains(word);
	}

	/** Java access level for a declaration; 'internal' has no counterpart. */
	static String accessOf(ParserRuleContext ctx){
		for(String word:modifiersOf(ctx)){
			if(word.equals("internal"))return "public";
			if(word.equals("private")||word.equals("protected")||word.equals("public"))
				return word;
		}
		return "public";
	}

	static KotlinParser.NoassignContext findNoassign(ParserRuleContext ctx){
		for(ParseTree c:children(ctx)){
			if(c instanceof KotlinParser.NoassignContext)return (KotlinParser.NoassignContext)c;
			if(c instanceof ParserRuleContext){
				KotlinParser.NoassignContext n=findNoassign((ParserRuleContext)c);
				if(n!=null)return n;
			}
		}
		return null;
	}

	// ------------------------------------------------------------ pass 2

	String translate(KotlinParser.ProgContext prog){
		List<String> members=new ArrayList<String>();
		push();
		for(ParseTree node:allStarts(prog)){
			if(node instanceof KotlinParser.DeclGlobalListContext){
				for(ParseTree g:children(node)){
					KotlinParser.GlobalDeclContext gd=(KotlinParser.GlobalDeclContext)g;
					members.add(accessOf(gd)+" static "+declaration(gd.withassign(),true));
				}
			}else if(node instanceof KotlinParser.FuncListContext){
				for(ParseTree f:children(node))
					members.add(function((KotlinParser.FunctionContext)f,false));
			}else if(node instanceof KotlinParser.ClassListContext){
				for(ParseTree c:children(node)){
					Cls k=classes.get(((KotlinParser.ClassDeclContext)c).ID().getText());
					if(k==null)continue;
					if(k.name.equals(mainClass)){
						ownClass=k;                 // emitted as the file's public class
						continue;
					}
					members.add(classBody(k));
				}
			}else if(node instanceof KotlinParser.InterfaceListContext){
				for(ParseTree c:children(node)){
					Cls k=classes.get(((KotlinParser.InterfaceDeclContext)c).ID().getText());
					if(k==null)continue;
					if(k.name.equals(mainClass)){
						ownClass=k;              // emitted as the file's public interface
						continue;
					}
					members.add(interfaceBody(k));
				}
			}
			members.addAll(hoisted);
			hoisted.clear();
		}
		pop();
		/* The file's own type is translated here, before the helpers and imports are
		   decided: what its body needs can only be known once it has been generated. */
		String ownBody=ownClass==null?null
			:ownClass.isInterface?interfaceBody(ownClass,true,members)
			:classBody(ownClass,true,members);
		/* One place closes the member list: whatever was hoisted while generating the
		   body above still belongs inside it, and the generated helpers join it. */
		List<String> extras=new ArrayList<String>(hoisted);
		hoisted.clear();
		if(ownBody==null)extras.addAll(0,members);
		members=extras;
		if(needsFind||needsMatchEntire)members.add(
			"private static List<String> regexGroups(Matcher m) "+braces(
				"List<String> groups = new ArrayList<>();\n"
				+"for (int i = 0; i <= m.groupCount(); i++) groups.add(m.group(i));\n"
				+"return groups;"));
		if(needsFind)members.add(
			"private static List<String> regexFind(Pattern p, String s) "+braces(
				"Matcher m = p.matcher(s);\nreturn m.find() ? regexGroups(m) : null;"));
		if(needsMatchEntire)members.add(
			"private static List<String> regexMatchEntire(Pattern p, String s) "+braces(
				"Matcher m = p.matcher(s);\nreturn m.matches() ? regexGroups(m) : null;"));
		if(needsFindAll)members.add(
			"private static List<String> regexFindAll(Pattern p, String s) "+braces(
				"List<String> all = new ArrayList<>();\n"
				+"Matcher m = p.matcher(s);\n"
				+"while (m.find()) all.add(m.group());\n"
				+"return all;"));
		if(needsLast)members.add(
			"private static <T> T lastOf(List<T> list) "+braces(
				"return list.get(list.size() - 1);"));
		if(needsJoin)members.add(
			"private static String joinToString(Collection<?> items, String separator) "
				+braces(
				"StringBuilder out = new StringBuilder();\n"
				+"for (Object item : items) "+braces(
					"if (out.length() > 0) out.append(separator);\n"
					+"out.append(item);")
				+"\nreturn out.toString();"));
		if(needsToIntOrNull)members.add(
			"private static Integer toIntOrNull(String s) "+braces(
				"try "+braces("return Integer.valueOf(s);")
				+" catch (NumberFormatException e) "+braces("return null;")));
		/* The header comes last: which imports are needed only becomes known while
		   the members are generated (java.util.regex, for instance). */
		StringBuilder sb=new StringBuilder();
		sb.append(GENERATED_MARK);
		if(sourceName!=null)sb.append(" from ").append(sourceName);
		sb.append("\n");
		for(String h:header)sb.append(h).append("\n");
		sb.append("import java.util.*;\n");
		if(needsRegex)sb.append("import java.util.regex.*;\n");
		if(needsCollectors)sb.append("import java.util.stream.Collectors;\n");
		sb.append("\n");
		if(ownBody!=null){
			sb.append(withMembers(ownBody,members)).append("\n");
			return sb.toString();
		}
		// nothing hoisted can be left over: the list above already absorbed it
		sb.append("public class ").append(mainClass).append(" {\n");
		for(int i=0;i<members.size();i++){
			sb.append(indent(members.get(i))).append("\n");
			if(i<members.size()-1)sb.append("\n");
		}
		sb.append("}\n");
		return sb.toString();
	}

	/** start: declGlobalList|funcList|classList|interfaceList, flattened in order. */
	List<ParseTree> allStarts(KotlinParser.ProgContext prog){
		List<ParseTree> out=new ArrayList<ParseTree>();
		for(ParseTree c:children(prog))
			if(c instanceof KotlinParser.ListContext)flattenList((ParserRuleContext)c,out);
		return out;
	}

	void flattenList(ParserRuleContext list,List<ParseTree> out){
		for(ParseTree c:children(list)){
			if(c instanceof KotlinParser.ListContext)flattenList((ParserRuleContext)c,out);
			else if(c instanceof KotlinParser.StartContext)out.add(c.getChild(0));
		}
	}

	// -------------------------------------------------------------- classes

	String classBody(Cls k){ return classBody(k,false,new ArrayList<String>()); }

	/* topLevel: this class is the file's own class, so it becomes the public class of
	   the generated file and the rest of the file's declarations become its members. */
	String classBody(Cls k,boolean topLevel,List<String> extra){
		StringBuilder sb=new StringBuilder();
		sb.append(topLevel?"public ":"static ");
		if(k.isAbstract)sb.append("abstract ");
		sb.append("class ").append(k.name);
		// a parent from a dependency lives inside that file's generated class
		if(k.superName!=null)sb.append(" extends ").append(emitType(k.superName));
		if(!k.interfaces.isEmpty()){
			List<String> names=new ArrayList<String>();
			for(String i:k.interfaces)names.add(emitType(i));
			sb.append(" implements ").append(String.join(", ",names));
		}
		sb.append(" {\n");

		Cls outer=current;
		current=k;
		List<String> members=new ArrayList<String>();
		StringBuilder fields=new StringBuilder();
		for(Map.Entry<String,String> f:k.fields.entrySet()){
			String access=k.fieldAccess.get(f.getKey());
			fields.append(access==null?"":access+" ")
				.append(k.finalFields.contains(f.getKey())?"final ":"")
				.append(emitType(f.getValue())).append(" ").append(f.getKey()).append(";\n");
		}
		if(fields.length()>0)members.add(fields.toString().trim());
		/* A property declared by an interface (or by an abstract parent) reaches Java as
		   getX(); a constructor property is a plain field, so the field also has to
		   expose the accessor that the parent promised. */
		for(Map.Entry<String,String> f:propertiesOf(k).entrySet())
			if(inheritedGetter(k,f.getKey()))
				members.add("@Override\npublic "+emitType(f.getValue())+" "
					+accessor(f.getKey())+"() "+braces("return "+f.getKey()+";"));
		push();
		for(Map.Entry<String,String> cp:k.ctorParams.entrySet())declare(cp.getKey(),cp.getValue());
		List<String> initStatements=new ArrayList<String>();
		Map<ParseTree,String> propertyFields=new LinkedHashMap<ParseTree,String>();
		inConstructor=true;
		/* One pass over the members collects the constructor's code: init blocks and the
		   initializers of the properties written in the body, in the order the source
		   wrote them, which is the order Kotlin runs them in. */
		if(k.ctx!=null&&k.ctx.classcompound()!=null)
			for(ParseTree m:memberNodes(k.ctx.classcompound())){
				if(m instanceof KotlinParser.InitBlockContext){
					KotlinParser.CompoundStmtContext body=
						((KotlinParser.InitBlockContext)m).compoundStmt();
					if(body.stmtList()!=null)initStatements.add(stmtList(body.stmtList()));
					continue;
				}
				KotlinParser.IdentifierStmtContext property=propertyStatement(m);
				if(property!=null)propertyFields.put(m,bodyProperty(property,initStatements));
			}
		inConstructor=false;
		pop();
		members.add(constructor(k,initStatements));
		members.addAll(extra);

		push();
		if(k.ctx!=null && k.ctx.classcompound()!=null){
			for(ParseTree m:memberNodes(k.ctx.classcompound())){
				if(m instanceof KotlinParser.GetterContext)
					members.add(getter((KotlinParser.GetterContext)m,k));
				else if(m instanceof KotlinParser.FunctionContext)
					members.add(function((KotlinParser.FunctionContext)m,true));
				else if(propertyFields.containsKey(m))members.add(propertyFields.get(m));
				else if(!(m instanceof KotlinParser.InitBlockContext)){
					String code=stmt(m);
					if(!code.isEmpty())members.add(code);
				}
			}
		}
		pop();
		current=outer;

		for(int i=0;i<members.size();i++){
			sb.append(indent(members.get(i))).append("\n");
			if(i<members.size()-1)sb.append("\n");
		}
		sb.append("}");
		return sb.toString();
	}

	/** The member as a property declaration, or null when it is something else. */
	static KotlinParser.IdentifierStmtContext propertyStatement(ParseTree member){
		if(!(member instanceof KotlinParser.IdentifierStmtContext))return null;
		KotlinParser.IdentifierStmtContext stmt=(KotlinParser.IdentifierStmtContext)member;
		return stmt.declare()==null?null:stmt;
	}

	/* A property of the class body. In Kotlin its initializer is constructor code, so
	   the field is declared bare and the assignment goes to the constructor: a Java
	   field initializer cannot see the constructor's parameters. */
	String bodyProperty(KotlinParser.IdentifierStmtContext stmt,List<String> initStatements){
		KotlinParser.DeclIDContext id=stmt.declare().declID();
		String prefix=hasVisibility(id)?accessOf(id)+" ":"";
		ParseTree inner=((ParserRuleContext)id.declTypeId()).getChild(0);
		if(inner instanceof KotlinParser.NoassignContext)
			return prefix+declarationNoInit((KotlinParser.NoassignContext)inner);
		KotlinParser.WithassignContext w=(KotlinParser.WithassignContext)inner;
		String name=declName(w);
		String type=declType(w);
		String value=code(w.assignexpr());
		declare(name,type);
		initStatements.add("this."+name+" = "+value+";");
		return prefix+(keywordOf(w).equals("val")?"final ":"")+emitType(type)+" "+name+";";
	}

	String constructor(Cls k,List<String> initStatements){
		List<String> ps=new ArrayList<String>();
		for(Map.Entry<String,String> p:k.ctorParams.entrySet())
			ps.add(emitType(p.getValue())+" "+p.getKey());
		StringBuilder body=new StringBuilder();
		String superArgs=superCall(k);
		if(superArgs!=null)body.append("super(").append(superArgs).append(");\n");
		for(String name:k.fields.keySet())
			body.append("this.").append(name).append(" = ").append(name).append(";\n");
		for(String init:initStatements)body.append(init).append("\n");
		return "public "+k.name+"("+String.join(", ",ps)+") "+braces(body.toString().trim());
	}

	/** Arguments of the superclass call in ': Shape(listOf(...))'. */
	String superCall(Cls k){
		if(k.ctx==null||k.superName==null)return null;
		for(KotlinParser.ClassORinterfaceContext ci:parents(k.ctx.extendsORimplements())){
			if(ci.getChildCount()==1)continue;
			push();
			for(Map.Entry<String,String> p:k.ctorParams.entrySet())declare(p.getKey(),p.getValue());
			String args=ci.argList()==null?"":argsOf(ci.argList());
			pop();
			return args;
		}
		return null;
	}

	/** extendsORimplements is left-recursive: flatten it to the listed parents. */
	static List<KotlinParser.ClassORinterfaceContext> parents(ParserRuleContext ctx){
		List<KotlinParser.ClassORinterfaceContext> out=
			new ArrayList<KotlinParser.ClassORinterfaceContext>();
		if(ctx==null)return out;
		for(ParseTree c:children(ctx)){
			if(c instanceof KotlinParser.ExtendsORimplementsContext)
				out.addAll(parents((ParserRuleContext)c));
			else if(c instanceof KotlinParser.ClassORinterfaceContext)
				out.add((KotlinParser.ClassORinterfaceContext)c);
		}
		return out;
	}

	String interfaceBody(Cls k){ return interfaceBody(k,false,new ArrayList<String>()); }

	/* Java allows one public type per file, so when the file's own declaration is an
	   interface the rest of the file becomes its static members, which interfaces have
	   accepted since Java 8. */
	String interfaceBody(Cls k,boolean topLevel,List<String> extra){
		List<String> parents=new ArrayList<String>();
		for(String i:k.interfaces)parents.add(emitType(i));
		StringBuilder sb=new StringBuilder((topLevel?"public ":"")+"interface "+k.name
			+(parents.isEmpty()?"":" extends "+String.join(", ",parents))+" {\n");
		List<String> members=new ArrayList<String>();
		Cls outer=current;
		current=k;
		// A Kotlin 'val x: T' in an interface is an abstract getter, not a field; one
		// written 'val x: T get() = ...' brings its own body, so it becomes a default.
		for(Map.Entry<String,String> g:k.getters.entrySet()){
			KotlinParser.GetterContext body=k.getterBodies.get(g.getKey());
			if(body==null)members.add(emitType(g.getValue())+" "+accessor(g.getKey())+"();");
			else members.add("default "+emitType(g.getValue())+" "
				+accessor(g.getKey())+"() "+braces("return "+code(body.assignexpr())+";"));
		}
		for(Fn fn:allDeclarations(k.methods)){
			String ret=returnTypeOf(fn);
			String head=emitType(ret)+" "+fn.name+"("+paramList(fn)+")";
			/* A Kotlin interface method may carry an implementation, which Java spells
			   'default'. Emitting the signature alone dropped the body. */
			members.add(fn.body==null?head+";":"default "+head+" "+bodyOf(fn,ret));
		}
		current=outer;
		members.addAll(extra);
		for(int i=0;i<members.size();i++)sb.append(indent(members.get(i))).append("\n");
		sb.append("}");
		return sb.toString();
	}

	String getter(KotlinParser.GetterContext g,Cls k){
		String name=getterName(g);
		String type=getterType(g);
		boolean override=hasModifier(g,"override");                    // 'open' is dropped
		push();
		String value=code(g.assignexpr());
		pop();
		return (override?"@Override\n":"")
			+accessOf(g)+" "+emitType(type)+" "+accessor(name)+"() "+braces("return "+value+";");
	}

	/** Every property backed by a field: written in the constructor or in the body. */
	Map<String,String> propertiesOf(Cls k){
		Map<String,String> all=new LinkedHashMap<String,String>(k.fields);
		all.putAll(k.bodyFields);
		return all;
	}

	/** True when an ancestor declares this name as a property, i.e. as a getter. */
	boolean inheritedGetter(Cls k,String name){
		for(Cls step:hierarchy(k))
			if(step!=k&&step.getters.containsKey(name))return true;
		return false;
	}

	static String accessor(String field){
		return "get"+Character.toUpperCase(field.charAt(0))+field.substring(1);
	}

	// ------------------------------------------------------------ functions

	String returnTypeOf(Fn fn){
		if(fn.declaredReturn!=null)return fn.declaredReturn;
		if(fn.body==null)return "void";
		if(fn.body.compoundStmt()!=null)return "void";       // no '=' body, no declared type
		if(!inferring.add(fn))return "Object";               // recursive inference
		try{
			push();
			for(int i=0;i<fn.paramNames.size();i++)declare(fn.paramNames.get(i),fn.paramTypes.get(i));
			String t=typeOfFuncassign(fn.body.funcassign());
			pop();
			return t==null?"Object":t;
		}finally{
			inferring.remove(fn);
		}
	}

	String paramList(Fn fn){
		List<String> ps=new ArrayList<String>();
		for(int i=0;i<fn.paramNames.size();i++)
			ps.add(emitType(fn.paramTypes.get(i))+" "+fn.paramNames.get(i));
		for(Map.Entry<String,String> c:fn.captured.entrySet())
			ps.add((c.getValue()==null?"Object":emitType(c.getValue()))+" "+c.getKey());
		return String.join(", ",ps);
	}

	String function(KotlinParser.FunctionContext ctx,boolean member){
		if(ctx.abstractFunction()!=null){
			Fn fn=signature(ctx);
			return "public abstract "+returnTypeOf(fn)+" "+fn.name+"("+paramList(fn)+");";
		}
		String name=ctx.memberName().getText();
		/* The tables are keyed by name, so with overloads the entry may describe a
		   different declaration: only reuse it when it is this very one (which is what
		   carries the captures of a hoisted local function). */
		Fn entry=current!=null&&current.methods.containsKey(name)?current.methods.get(name)
			:functions.get(name);
		// the entry may describe a different overload; this one is the declaration here
		Fn known=declarationFor(entry,ctx);
		Fn fn=known!=null?known:signature(ctx);

		if(fn.name.equals("main")&&!member)
			return "public static void main(String[] args) "+bodyOf(fn,"void");

		String ret=returnTypeOf(fn);
		StringBuilder sb=new StringBuilder();
		if(fn.isOverride)sb.append("@Override\n");
		sb.append(fn.access).append(member?" ":" static ");
		sb.append(emitType(ret)).append(" ").append(fn.name).append("(")
			.append(paramList(fn)).append(") ");
		sb.append(bodyOf(fn,ret));
		return sb.toString();
	}

	String bodyOf(Fn fn,String ret){
		int savedBase=scopeBase;
		scopeBase=scopes.size();               // the function's own scopes start here
		push();
		for(int i=0;i<fn.paramNames.size();i++)declare(fn.paramNames.get(i),fn.paramTypes.get(i));
		String out;
		if(fn.body.compoundStmt()!=null)out=compound(fn.body.compoundStmt());
		else out=braces(funcassign(fn.body.funcassign(),ret));
		pop();
		scopeBase=savedBase;
		return out;
	}

	/** funcassign: '=' (assignexpr|withElse|whenStmt) -- the body is the value. */
	String funcassign(KotlinParser.FuncassignContext ctx,String ret){
		ParseTree v=ctx.getChild(1);
		// 'when' can now arrive wrapped in an assignexpr; unwrap so the body form is used
		if(v instanceof KotlinParser.AssignexprContext&&v.getChildCount()==1
				&&v.getChild(0) instanceof KotlinParser.WhenStmtContext)v=v.getChild(0);
		if(v instanceof KotlinParser.WhenStmtContext)
			return whenStmt((KotlinParser.WhenStmtContext)v,true);
		if(v instanceof KotlinParser.WithElseContext){
			KotlinParser.WithElseContext w=(KotlinParser.WithElseContext)v;
			return "if ("+condition(w.condition())+") "+returningBlock(w.compoundStmt(0))
				+" else "+(w.ifStmt()!=null?ifStmt(w.ifStmt())
					:returningBlock(w.compoundStmt(1)));
		}
		return "return "+code(v)+";";
	}

	/** A block whose lone expression becomes the returned value. */
	String returningBlock(KotlinParser.CompoundStmtContext ctx){
		return braces(returningStatements(ctx));
	}

	/* The same, without the braces: the caller decides where the statements go. A block
	   of one expression is that expression being returned; anything else stands as it
	   is, and a missing return is then javac's to report. */
	String returningStatements(KotlinParser.CompoundStmtContext ctx){
		if(ctx.stmtList()!=null&&ctx.stmtList().getChildCount()==1){
			ParseTree only=ctx.stmtList().getChild(0).getChild(0);
			if(!(only instanceof KotlinParser.RetStmtContext))return "return "+code(only)+";";
		}
		return statements(ctx);
	}

	// ----------------------------------------------------------- statements

	String compound(KotlinParser.CompoundStmtContext ctx){ return braces(statements(ctx)); }

	/** The statements of a block, in their own scope, without the braces. */
	String statements(KotlinParser.CompoundStmtContext ctx){
		push();
		String body=ctx.stmtList()==null?"":stmtList(ctx.stmtList());
		pop();
		return body;
	}

	String stmtList(KotlinParser.StmtListContext ctx){
		List<String> out=new ArrayList<String>();
		List<String[]> guards=new ArrayList<String[]>();
		for(ParseTree s:children(ctx)){
			ParseTree statement=s.getChild(0);
			String code=stmt(statement);
			if(!code.isEmpty())out.add(code);
			/* 'if (x !is T) return' means every later statement runs with x narrowed,
			   which is how Kotlin reads the guard. */
			String[] cast=guardNarrowing(statement);
			if(cast!=null)guards.add(pushNarrowing(cast[0],cast[1]));
		}
		for(int i=guards.size()-1;i>=0;i--)popNarrowing(guards.get(i));
		return String.join("\n",out);
	}

	/** The narrowing left behind by a guard of the form 'if (x !is T) return'. */
	String[] guardNarrowing(ParseTree statement){
		if(!(statement instanceof KotlinParser.IfStmtContext))return null;
		ParseTree form=statement.getChild(0);
		if(!(form instanceof KotlinParser.NoElseContext)
				&&!(form instanceof KotlinParser.ShortIfContext))return null;
		ParserRuleContext ctx=(ParserRuleContext)form;
		if(ctx.getChildCount()>5)return null;                 // has an else: no guard
		KotlinParser.ConditionContext cond=form instanceof KotlinParser.NoElseContext
			?((KotlinParser.NoElseContext)form).condition()
			:((KotlinParser.ShortIfContext)form).condition();
		if(!exits(ctx.getChild(4)))return null;
		return narrowingOf(cond,true);
	}

	/* True when this branch always leaves: 'return', but 'break' and 'continue' just
	   as much. What follows a guard runs only when the guard did not hold, which is
	   what lets the rest of the block rely on the narrower type. */
	static boolean exits(ParseTree body){
		if(body instanceof KotlinParser.RetStmtContext
				||body instanceof KotlinParser.JumpStmtContext)return true;
		if(body instanceof KotlinParser.CompoundStmtContext){
			KotlinParser.CompoundStmtContext block=(KotlinParser.CompoundStmtContext)body;
			if(block.stmtList()==null)return false;
			List<ParseTree> statements=children(block.stmtList());
			return exits(statements.get(statements.size()-1).getChild(0));
		}
		return false;
	}

	String stmt(ParseTree node){
		if(node instanceof KotlinParser.StmtContext)return stmt(node.getChild(0));
		if(node instanceof KotlinParser.ClassStmtContext)return stmt(node.getChild(0));
		if(node instanceof KotlinParser.FunctionContext){
			// Java has no local functions: hoist it as a sibling static method, with
			// whatever it captures from the enclosing scope added as parameters.
			KotlinParser.FunctionContext f=(KotlinParser.FunctionContext)node;
			liftCaptures(f);
			hoisted.add(function(f,false));
			return "";
		}
		if(node instanceof KotlinParser.CallStmtContext)return code(node.getChild(0))+";";
		if(node instanceof KotlinParser.JumpStmtContext)return node.getText()+";";
		if(node instanceof KotlinParser.RetStmtContext)return retStmt((KotlinParser.RetStmtContext)node);
		if(node instanceof KotlinParser.IfStmtContext)return ifStmt((KotlinParser.IfStmtContext)node);
		if(node instanceof KotlinParser.IdentifierStmtContext)
			return identifierStmt((KotlinParser.IdentifierStmtContext)node);
		if(node instanceof KotlinParser.ForStmtContext)return forStmt((KotlinParser.ForStmtContext)node);
		if(node instanceof KotlinParser.WhileStmtContext){
			KotlinParser.WhileStmtContext w=(KotlinParser.WhileStmtContext)node;
			return "while ("+condition(w.condition())+") "+compound(w.compoundStmt());
		}
		if(node instanceof KotlinParser.WhenStmtContext)
			return whenStmt((KotlinParser.WhenStmtContext)node,false);
		if(node instanceof KotlinParser.LambdaexpressionContext)
			return lambda((KotlinParser.LambdaexpressionContext)node)+";";
		String code=code(node);
		/* A statement that is just a name or a literal does nothing, and in practice it
		   means the line was not understood the way it was written. */
		if(isSimpleName(code)||Character.isDigit(code.charAt(0))||code.charAt(0)=='"')
			warn("the statement '"+code+"' has no effect; the line may not have been"
				+" read as intended");
		return code+";";
	}

	String retStmt(KotlinParser.RetStmtContext ctx){
		if(ctx.getChildCount()==1)return "return;";
		ParseTree value=ctx.getChild(1);
		// the value arrives wrapped in an assignexpr; unwrap it so the shapes below match
		if(value instanceof KotlinParser.AssignexprContext&&value.getChildCount()==1)
			value=value.getChild(0);
		// a returned 'when' becomes an if/else chain with a return in every branch,
		// which reads better than nesting conditionals
		if(value instanceof KotlinParser.WhenStmtContext)
			return whenStmt((KotlinParser.WhenStmtContext)value,true);
		return "return "+code(value)+";";
	}

	/* The name and type of a test of the exact shape 'x is T' (or 'x !is T' when
	   'negated' is asked for), on any node that can hold one. */
	String[] narrowingOf(ParseTree node,boolean negated){
		while(node!=null&&node.getChildCount()==1
				&&!(node instanceof KotlinParser.LogsidesContext))node=node.getChild(0);
		/* A test joined to others still narrows, as long as the operator carries the
		   weight: relying on the condition being TRUE, 'x is T && rest' proves the test
		   held, whichever side it was written on. A guard relies on the condition being
		   FALSE, and there it is '||' that proves it ('x !is T || rest' being false
		   means x is T). The other operator proves nothing either way. */
		if(node instanceof KotlinParser.LogexpressionContext&&node.getChildCount()==3
				&&node.getChild(1).getText().equals(negated?"||":"&&")){
			String[] left=narrowingOf(node.getChild(0),negated);
			return left!=null?left:narrowingOf(node.getChild(2),negated);
		}
		if(!(node instanceof KotlinParser.LogsidesContext))return null;
		KotlinParser.LogsidesContext sides=(KotlinParser.LogsidesContext)node;
		if(sides.getChildCount()!=2||sides.logsuffix()==null)return null;
		KotlinParser.LogsuffixContext suffix=sides.logsuffix();
		boolean isNegated=suffix.getChild(0).getText().equals("!");
		if(isNegated!=negated)return null;
		int at=negated?1:0;
		if(!suffix.getChild(at).getText().equals("is"))return null;
		String name=sides.getChild(0).getText();
		if(!isSimpleName(name))return null;
		return new String[]{name,typeName(suffix.getChild(at+1).getText(),true)};
	}

	String[] narrowing(KotlinParser.ConditionContext ctx){
		return narrowingOf(ctx,false);
	}

	/* Narrowings nest, so the previous one is remembered and put back: dropping it
	   would lose the outer 'if (a is Dog)' as soon as an inner 'if (a is Puppy)' ends. */
	String[] pushNarrowing(String name,String type){
		if(name==null||type==null)return null;   // nothing to narrow
		String[] saved=new String[]{name,smartCasts.get(name)};
		smartCasts.put(name,type);
		return saved;
	}

	String[] pushNarrowing(KotlinParser.ConditionContext ctx){
		String[] cast=narrowing(ctx);
		return cast==null?null:pushNarrowing(cast[0],cast[1]);
	}

	void popNarrowing(String[] saved){
		if(saved==null)return;
		if(saved[1]==null)smartCasts.remove(saved[0]);
		else smartCasts.put(saved[0],saved[1]);
	}

	/** The type a when branch of the form 'is T' narrows its subject to. */
	String branchCast(KotlinParser.WhenKeysContext keys){
		if(keys==null||keys.expr().size()!=1)return null;
		KotlinParser.LogsidesContext suffix=bareSuffix(keys.expr(0));
		if(suffix==null||!suffix.getChild(0).getText().equals("is"))return null;
		return typeName(suffix.getChild(1).getText(),true);
	}

	static boolean isSimpleName(String text){
		for(int i=0;i<text.length();i++)
			if(!Character.isLetterOrDigit(text.charAt(i))&&text.charAt(i)!='_')return false;
		return text.length()>0;
	}

	String ifStmt(KotlinParser.IfStmtContext ctx){
		ParseTree k=ctx.getChild(0);
		if(k instanceof KotlinParser.WithElseContext){
			KotlinParser.WithElseContext w=(KotlinParser.WithElseContext)k;
			String test=condition(w.condition());
			String[] saved=pushNarrowing(w.condition());
			String then=compound(w.compoundStmt(0));
			popNarrowing(saved);
			return "if ("+test+") "+then+" else "+elseBranch(w);
		}
		if(k instanceof KotlinParser.NoElseContext){
			KotlinParser.NoElseContext n=(KotlinParser.NoElseContext)k;
			String test=condition(n.condition());
			String[] saved=pushNarrowing(n.condition());
			String then=compound(n.compoundStmt());
			popNarrowing(saved);
			return "if ("+test+") "+then;
		}
		KotlinParser.ShortIfContext s=(KotlinParser.ShortIfContext)k;
		String test=condition(s.condition());
		String[] saved=pushNarrowing(s.condition());
		String out="if ("+test+") "+braces(stmt(s.getChild(4)));
		popNarrowing(saved);
		if(s.getChildCount()>5)out+=" else "+braces(stmt(s.getChild(6)));
		return out;
	}

	/** The else of a withElse: a block, or another if for an 'else if' chain. */
	String elseBranch(KotlinParser.WithElseContext w){
		if(w.ifStmt()!=null)return ifStmt(w.ifStmt());
		return compound(w.compoundStmt(1));
	}

	/** identifierStmt: declare | exprID ('+='|'*='|'/='|'-='|'=') assignexpr */
	String identifierStmt(KotlinParser.IdentifierStmtContext ctx){
		if(ctx.declare()!=null){
			/* Only a class or file property can carry a visibility; Kotlin does not
			   allow one on a local, so finding one means this is a member. */
			String prefix=hasVisibility(ctx.declare().declID())
				?accessOf(ctx.declare().declID())+" ":"";
			KotlinParser.NoassignContext n=null;
			KotlinParser.WithassignContext w=null;
			ParserRuleContext d=(ParserRuleContext)ctx.declare().declID().declTypeId();
			ParseTree inner=d.getChild(0);
			if(inner instanceof KotlinParser.WithassignContext)w=(KotlinParser.WithassignContext)inner;
			else n=(KotlinParser.NoassignContext)inner;
			return prefix+(w!=null?declaration(w,false):declarationNoInit(n));
		}
		String op=ctx.getChild(1).getText();
		if(ctx.getChild(0) instanceof KotlinParser.ListIDContext){
			/* Kotlin writes an element with 'xs[i] = v', which is List.set; a compound
			   assignment reads it first. The index is a name or a literal, so naming it
			   twice evaluates nothing twice. */
			KotlinParser.ListIDContext l=(KotlinParser.ListIDContext)ctx.getChild(0);
			String list=receiverOf(l);
			String index=l.getChild(2).getText();
			String value=code(ctx.assignexpr());
			if(!op.equals("="))
				value=list+".get("+index+") "+op.charAt(0)+" "+group(value);
			return list+".set("+index+", "+value+");";
		}
		String lhs=code(ctx.getChild(0));            // an exprID or a longer path
		String type=typeOf(ctx.getChild(0));
		/* On a collection, Kotlin's '+=' adds an element and '-=' removes one (plusAssign
		   and minusAssign); Java has no operator for either. They go through memberCall
		   so that the by-value removal on a list of integers is decided in one place. */
		if(elem(type)!=null&&(op.equals("+=")||op.equals("-=")))
			return memberCall(lhs,type,op.equals("+=")?"add":"remove",
				code(ctx.assignexpr()),null)+";";
		return lhs+" "+op+" "+code(ctx.assignexpr())+";";
	}

	String declarationNoInit(KotlinParser.NoassignContext n){
		String name=n.ID().getText();
		String type=noassignType(n);
		declare(name,type);
		return (n.keyword().getText().equals("val")?"final ":"")+emitType(type)+" "+name+";";
	}

	String declaration(KotlinParser.WithassignContext w,boolean global){
		String name=declName(w);
		String type=declType(w);
		String value=code(w.assignexpr());
		declare(name,type);
		if(global)globals.put(name,type);
		return (keywordOf(w).equals("val")?"final ":"")+emitType(type)+" "+name+" = "+value+";";
	}

	/** withassign: noassign '=' assignexpr | keyword ID '=' assignexpr */
	static String declName(KotlinParser.WithassignContext w){
		return w.noassign()!=null?w.noassign().ID().getText():w.ID().getText();
	}

	static String keywordOf(KotlinParser.WithassignContext w){
		return w.noassign()!=null?w.noassign().keyword().getText():w.keyword().getText();
	}

	String declType(KotlinParser.WithassignContext w){
		if(w.noassign()!=null)return noassignType(w.noassign());
		String t=typeOf(w.assignexpr());
		return t==null?"Object":t;
	}

	String temp(String hint){ return hint+(temps++); }

	/* A Kotlin range is built before the loop runs, so its end and its step are read
	   once. The emitted condition reads them on every turn instead, which only agrees
	   when the value cannot change: anything else is held in a local. */
	String rangeBound(ParseTree node,String hint,Set<String> assigned,List<String> held){
		String c=code(node);
		Set<String> refs=new LinkedHashSet<String>();
		collectReferences(node,refs);
		refs.retainAll(assigned);
		if(refs.isEmpty()&&!hasCall(node))return c;
		String name=temp(hint);
		held.add(name+" = "+c);
		return name;
	}

	static String forInit(String var,String from,List<String> held){
		String init="int "+var+" = "+from;
		for(String h:held)init+=", "+h;
		return init;
	}

	String forStmt(KotlinParser.ForStmtContext ctx){
		String var=ctx.ID().getText();
		ParseTree src=ctx.getChild(4);
		if(src instanceof KotlinParser.ExplicitRangeContext){
			KotlinParser.ExplicitRangeContext r=(KotlinParser.ExplicitRangeContext)src;
			String kind=r.getChild(1).getText();
			boolean down=kind.equals("downTo");
			Set<String> assigned=new LinkedHashSet<String>();
			collectAssigned(ctx.compoundStmt(),assigned);
			List<String> held=new ArrayList<String>();
			String from=code(r.getChild(0));
			String to=rangeBound(r.getChild(2),"rangeEnd",assigned,held);
			String step=r.getChildCount()>3
				?rangeBound(r.getChild(4),"rangeStep",assigned,held):"1";
			push();
			declare(var,"int");
			String compare=down?" >= ":kind.equals("until")?" < ":" <= ";
			String head="for ("+forInit(var,from,held)+"; "+var+compare+to+"; "
				+var+(down?" -= ":" += ")+step+") ";
			String out=head+compound(ctx.compoundStmt());
			pop();
			return out;
		}
		if(src instanceof KotlinParser.ExprIDContext){
			KotlinParser.ExprIDContext id=(KotlinParser.ExprIDContext)src;
			if(id.getChildCount()==3&&id.getChild(2).getText().equals("indices")){
				String list=receiverOf(id);
				String size=temp("rangeSize");
				push();
				declare(var,"int");
				String out="for ("+forInit(var,"0",Arrays.asList(size+" = "+list+".size()"))
					+"; "+var+" < "+size+"; "+var+"++) "
					+compound(ctx.compoundStmt());
				pop();
				return out;
			}
		}
		String seq=code(src);
		String type=elem(typeOf(src));
		push();
		declare(var,type);
		String out="for ("+(type==null?"var":emitType(type))+" "+var+" : "+seq+") "
			+compound(ctx.compoundStmt());
		pop();
		return out;
	}

	/** whenStmt as a statement, or as the returned value of a function. */
	/* Reads the subject and the branches. 'allowTemp' says whether a subject that
	   must not be evaluated twice can be put in a local, which only the statement
	   form can do. */
	WhenParts readWhen(KotlinParser.WhenStmtContext ctx,boolean allowTemp){
		WhenParts parts=new WhenParts();
		if(ctx.getChild(1).getText().equals("(")){
			parts.subject=code(ctx.getChild(2));
			parts.subjectType=typeOf(ctx.getChild(2));
			// the subject is compared in every branch, so a call in it must not repeat
			if(hasCall(ctx.getChild(2))){
				if(!allowTemp){
					errors.add("a when used as a value cannot have a call as its subject;"
						+" assign it to a variable first");
					parts.failed=true;
					return parts;
				}
				String held=temp("whenSubject");
				parts.prelude=(parts.subjectType==null?"Object":emitType(parts.subjectType))
					+" "+held+" = "+parts.subject+";";
				parts.subject=held;
			}
		}
		for(ParseTree c:children(ctx)){
			if(!(c instanceof KotlinParser.WhenexprContext))continue;
			KotlinParser.WhenexprContext w=(KotlinParser.WhenexprContext)c;
			if(w.getChild(0).getText().equals("else")){
				parts.elseBody=w.getChild(2);
				continue;
			}
			parts.tests.add(whenConditions(w.whenKeys(),parts.subject,parts.subjectType));
			parts.bodies.add(w.getChild(2));
			parts.casts.add(parts.subject!=null&&isSimpleName(parts.subject)
				?branchCast(w.whenKeys()):null);
		}
		return parts;
	}

	/** Translates a branch body with that branch's narrowing in force. */
	String whenBranch(WhenParts parts,int index,boolean returning){
		String[] saved=pushNarrowing(parts.subject,parts.casts.get(index));
		String body=whenBody(parts.bodies.get(index),returning);
		popNarrowing(saved);
		return body;
	}

	String whenStmt(KotlinParser.WhenStmtContext ctx,boolean returning){
		WhenParts parts=readWhen(ctx,true);
		List<String> chain=new ArrayList<String>();
		for(int i=0;i<parts.tests.size();i++)
			chain.add("if ("+parts.tests.get(i)+") "+braces(whenBranch(parts,i,returning)));
		// the join below supplies the 'else'; with no branches at all the body stands
		// alone, since an 'else' without an 'if' is not Java
		if(parts.elseBody!=null)chain.add(braces(whenBody(parts.elseBody,returning)));
		String out=String.join(" else ",chain);
		return parts.prelude==null?out:"{\n"+indent(parts.prelude+"\n"+out)+"\n}";
	}

	/* A when used as a value: Java has no when expression, so the branches become a
	   chain of conditionals. Without an else there would be no value to fall back to,
	   which Kotlin also rejects. */
	String whenExpression(KotlinParser.WhenStmtContext ctx){
		WhenParts parts=readWhen(ctx,false);
		if(parts.failed)return "null";
		for(ParseTree body:parts.bodies){
			if(body instanceof KotlinParser.RetStmtContext)
				return cannotTranslate("a when used as a value cannot 'return' from its"
					+" branches");
			if(body instanceof KotlinParser.JumpStmtContext)
				return cannotTranslate("a when used as a value cannot '"+body.getText()
					+"' from its branches");
			if(body instanceof KotlinParser.CompoundStmtContext)
				return cannotTranslate("a when used as a value cannot have a block branch;"
					+" a conditional takes an expression, not statements");
		}
		if(parts.elseBody==null)
			return cannotTranslate("a when used as a value needs an else branch");
		String out=code(parts.elseBody);
		for(int i=parts.tests.size()-1;i>=0;i--){
			String[] saved=pushNarrowing(parts.subject,parts.casts.get(i));
			String value=code(parts.bodies.get(i));
			popNarrowing(saved);
			out=parts.tests.get(i)+" ? "+value+" : "+out;
		}
		return parts.tests.isEmpty()?out:"("+out+")";
	}

	String whenBody(ParseTree body,boolean returning){
		// a block branch: the braces come from the caller, which is building an if/else
		if(body instanceof KotlinParser.CompoundStmtContext)
			return returning?returningStatements((KotlinParser.CompoundStmtContext)body)
				:statements((KotlinParser.CompoundStmtContext)body);
		if(body instanceof KotlinParser.JumpStmtContext)return body.getText()+";";
		if(body instanceof KotlinParser.RetStmtContext)
			return retStmt((KotlinParser.RetStmtContext)body);
		return returning?"return "+code(body)+";":code(body)+";";
	}

	/** Several keys in one branch match if any of them does. */
	String whenConditions(KotlinParser.WhenKeysContext keys,String subject,String subjectType){
		List<String> tests=new ArrayList<String>();
		for(KotlinParser.ExprContext key:keys.expr())
			tests.add(whenCondition(key,subject,subjectType));
		return tests.size()==1?tests.get(0):"("+String.join(" || ",tests)+")";
	}

	/** Turns a when branch into a boolean test against the subject. */
	String whenCondition(KotlinParser.ExprContext e,String subject,String subjectType){
		KotlinParser.LogsidesContext suffix=bareSuffix(e);
		// the subject of a when is held in a local when it is a call, so it is safe to repeat
		if(suffix!=null&&subject!=null)
			return logsidesSuffix(suffix,subject,subjectType,false,true);
		if(subject==null)return code(e);
		String value=code(e);
		if(subjectType==null||isReference(subjectType))
			return "Objects.equals("+subject+", "+value+")";
		return subject+" == "+value;
	}

	/** A branch like 'is Long' or '!is String', which has no left operand. */
	KotlinParser.LogsidesContext bareSuffix(KotlinParser.ExprContext e){
		if(e.logexpression()==null)return null;
		KotlinParser.LogexpressionContext l=e.logexpression();
		if(l.logsides()==null)return null;
		KotlinParser.LogsidesContext s=l.logsides();
		String first=s.getChild(0).getText();
		if(first.equals("is")||first.equals("in"))return s;
		if(first.equals("!")&&s.getChildCount()==2){
			String inner=s.logsides().getChild(0).getText();
			if(inner.equals("is")||inner.equals("in"))return s;
		}
		return null;
	}

	// ---------------------------------------------------------- expressions

	String condition(KotlinParser.ConditionContext ctx){ return code(ctx.getChild(0)); }

	/** Dispatches any expression-ish node to its translation. */
	String code(ParseTree n){
		if(n instanceof KotlinParser.ExprContext
				||n instanceof KotlinParser.AssignexprContext
				||n instanceof KotlinParser.ConditionContext)return code(n.getChild(0));
		if(n instanceof KotlinParser.StringLiteralContext)
			return template(n.getText());
		if(n instanceof KotlinParser.NumContext)return numLiteral(n.getText());
		if(n instanceof KotlinParser.ExpressionContext)
			return expression((KotlinParser.ExpressionContext)n);
		if(n instanceof KotlinParser.ExprIDContext)return exprID((KotlinParser.ExprIDContext)n);
		if(n instanceof KotlinParser.ListIDContext){
			KotlinParser.ListIDContext l=(KotlinParser.ListIDContext)n;
			return receiverOf(l)+".get("+l.getChild(2).getText()+")";
		}
		if(n instanceof KotlinParser.CallContext)return call((KotlinParser.CallContext)n);
		if(n instanceof KotlinParser.ChainContext)return chain((KotlinParser.ChainContext)n);
		if(n instanceof KotlinParser.LogexpressionContext)
			return logexpression((KotlinParser.LogexpressionContext)n);
		if(n instanceof KotlinParser.LogsidesContext)
			return logsides((KotlinParser.LogsidesContext)n);
		if(n instanceof KotlinParser.ShortIfContext){
			KotlinParser.ShortIfContext s=(KotlinParser.ShortIfContext)n;
			if(s.getChildCount()<=5)return stmt(s);           // statement, not a value
			return condition(s.condition())+" ? "+code(s.getChild(4))+" : "+code(s.getChild(6));
		}
		if(n instanceof KotlinParser.RetStmtContext)return retStmt((KotlinParser.RetStmtContext)n);
		if(n instanceof KotlinParser.LambdaexpressionContext)
			return lambda((KotlinParser.LambdaexpressionContext)n);
		if(n instanceof KotlinParser.WhenStmtContext)
			return whenExpression((KotlinParser.WhenStmtContext)n);
		return cannotTranslate("cannot translate "+n.getClass().getSimpleName()
			.replace("Context","")+" '"+n.getText()+"'");
	}

	String expression(KotlinParser.ExpressionContext c){
		if(c.num()!=null)return numLiteral(c.num().getText());
		if(c.listID()!=null)return code(c.listID());
		if(c.getChildCount()==1)return code(c.getChild(0));
		if(c.getChildCount()==2){
			if(c.getChild(0).getText().equals("-")){
				String inner=code(c.getChild(1));
				// '- -a' written plainly would read as Java's pre-decrement '--a'
				return inner.startsWith("-")?"-("+inner+")":"-"+inner;
			}
			return code(c.getChild(0))+c.getChild(1).getText();          // x++ / x--
		}
		if(c.getChild(0).getText().equals("("))return "("+code(c.getChild(1))+")";
		return code(c.getChild(0))+" "+c.getChild(1).getText()+" "+code(c.getChild(2));
	}

	/** exprID: ID | ID '.' ID */
	String exprID(KotlinParser.ExprIDContext c){
		String name=c.getChild(0).getText();
		if(c.getChildCount()==1)return identifierCode(name);
		return memberRead(identifierCode(name),lookup(name),c.getChild(2).getText());
	}

	/* Receivers are written as plain names in several rules (a[i], a.b(), a.indices,
	   a.filter {}). Reading the name straight off the tree loses the resolution that
	   identifierCode performs, so every one of them comes through here. */
	String receiverOf(ParseTree rule){
		return identifierCode(rule.getChild(0).getText());
	}

	/** How a bare identifier is written: narrowed, accessor, qualified or plain. */
	String identifierCode(String name){
		if(smartCasts.containsKey(name))return "(("+smartCasts.get(name)+") "+name+")";
		/* Inside the constructor a property is shadowed by the parameter of the same
		   name, so an init block referring to it has to say 'this'. */
		if(inConstructor&&current!=null&&current.fields.containsKey(name))
			return "this."+name;
		// a local or a parameter shadows a property of the class, as in Kotlin
		if(inLocalScopes(name))return name;
		if(current!=null&&isGetter(current,name))return accessor(name)+"()";
		if(globalHome.containsKey(name))return globalHome.get(name)+"."+name;
		// kotlin.math constants, unless this file declares something with that name
		if((name.equals("PI")||name.equals("E"))&&lookup(name)==null)return "Math."+name;
		return name;
	}

	/* Reading a property off a receiver. Shared by 'a.b', by the links of a chain
	   and by the safe-call form, so all three agree on the Kotlin-to-Java mapping
	   of length/size/lastIndex and of properties backed by a getter. */
	String memberRead(String recvRaw,String recvType,String member){
		String recv=group(recvRaw);
		/* A class of this file is consulted first: otherwise a property of its own
		   named 'size' or 'length' would get the collection treatment. */
		Cls own=classOf(recvType);
		if(own!=null)
			return isGetter(own,member)?recv+"."+accessor(member)+"()":recv+"."+member;
		if(member.equals("length"))
			return ("Object".equals(recvType)?"((String) "+recv+")":recv)+".length()";
		if(member.equals("size"))return recv+".size()";
		// parenthesised: 'a.lastIndex * 2' must not read as 'a.size() - (1 * 2)'
		if(member.equals("lastIndex"))
			return "("+recv+"."+("String".equals(recvType)?"length":"size")+"() - 1)";
		/* 'indices' is a range, and Java has no value for one. The two forms that do
		   not need the value -- 'for (i in x.indices)' and 'i in x.indices' -- are
		   read before this point; anything else would quietly become a size. */
		if(member.equals("indices"))
			return cannotTranslate("'"+member+"' is only supported as 'for (i in x."
				+member+")' or 'i in x."+member+"'");
		Cls k=classOf(recvType);
		if(k!=null&&isGetter(k,member))return recv+"."+accessor(member)+"()";
		if(MATCH.equals(recvType)){
			if(member.equals("value"))return recv+".get(0)";
			if(member.equals("groupValues"))return recv;      // it already is that list
		}
		return recv+"."+member;
	}

	String memberReadType(String recvType,String member){
		if(MATCH.equals(recvType)){
			if(member.equals("value"))return "String";
			if(member.equals("groupValues"))return "List<String>";
		}
		Cls own=classOf(recvType);
		if(own!=null)return memberType(own,member);
		if(member.equals("length")||member.equals("size")||member.equals("lastIndex"))return "int";
		return null;
	}

	static final Set<String> STRING_METHODS = new HashSet<String>(Arrays.asList(
		"replace","startsWith","endsWith","toUpperCase","toLowerCase","uppercase",
		"lowercase","trim","substring",
		"indexOf","isEmpty","split","contains","toIntOrNull","repeat","charAt"));

	/** call: callName '(' argList? ')' | ID '.' ID '(' argList? ')' */
	String call(KotlinParser.CallContext c){
		String args=c.argList()==null?"":argsOf(c.argList());
		if(c.callName()!=null)
			return namedCall(c.callName().getText(),args,argParts(c.argList()).size());
		String recv=c.getChild(0).getText();
		return memberCall(receiverOf(c),lookup(recv),c.getChild(2).getText(),args,
			c.argList());
	}

	/* kotlin.math is a set of top-level functions that map one to one onto
	   java.lang.Math, so they are rewritten rather than translated. */
	static final Map<String,String> MATH=new HashMap<String,String>();
	static {
		for(String f:new String[]{"sqrt","abs","max","min","pow","floor","ceil",
				"exp","log10","sin","cos","tan","asin","acos","atan","atan2","hypot","cbrt",
				"sinh","cosh","tanh","signum"})
			MATH.put(f,f);
		MATH.put("ln","log");                      // Kotlin spells the natural log 'ln'
		MATH.put("log2","log");                    // caller must divide, reported below
		MATH.put("sign","signum");                 // Kotlin drops the 'um'
		/* Kotlin's round is rint: it answers a Double and rounds a tie to the even
		   integer. Math.round answers a long and rounds a tie up, so it printed '3'
		   where Kotlin prints '2.0'. Rounding to an integer is roundToInt in Kotlin,
		   and THAT one is Math.round. */
		MATH.put("round","rint");
	}

	/* Kotlin's Regex methods in terms of java.util.regex. Anything outside this set
	   is reported instead of being guessed at, since a wrong regex call would fail
	   silently at run time. */
	String regexCall(String recv,String method,List<String> args){
		needsRegex=true;
		String first=args.isEmpty()?"":args.get(0);
		if(method.equals("matches"))return recv+".matcher("+first+").matches()";
		if(method.equals("containsMatchIn"))return recv+".matcher("+first+").find()";
		if(method.equals("replace")&&args.size()==2)
			return recv+".matcher("+first+").replaceAll("+args.get(1)+")";
		if(method.equals("replaceFirst")&&args.size()==2)
			return recv+".matcher("+first+").replaceFirst("+args.get(1)+")";
		// -1: see the note on String.split below, Kotlin keeps the empty fields
		if(method.equals("split"))return "List.of("+recv+".split("+first+", -1))";
		if(method.equals("pattern")||method.equals("toString"))return recv+".pattern()";
		if(method.equals("find")){
			needsFind=true;
			return "regexFind("+recv+", "+first+")";
		}
		if(method.equals("matchEntire")){
			needsMatchEntire=true;
			return "regexMatchEntire("+recv+", "+first+")";
		}
		if(method.equals("findAll")){
			needsFindAll=true;
			return "regexFindAll("+recv+", "+first+")";
		}
		errors.add("Regex."+method+" has no direct java.util.regex equivalent");
		return recv+"."+method+"("+String.join(", ",args)+")";
	}

	static String regexCallType(String method){
		if(method.equals("matches")||method.equals("containsMatchIn"))return "boolean";
		if(method.equals("find")||method.equals("matchEntire"))return MATCH;
		if(method.equals("findAll"))return "List<String>";
		if(method.equals("replace")||method.equals("replaceFirst"))return "String";
		if(method.equals("split"))return "List<String>";
		return "String";
	}

	/** A call by plain name: a builtin, a constructor or a function of this file. */
	String namedCall(String name,String args){ return namedCall(name,args,-1); }

	String namedCall(String name,String args,int argCount){
		if(name.equals("println")||name.equals("print"))return "System.out."+name+"("+args+")";
		/* List.of and Set.of reject null elements at RUN TIME, while Kotlin's listOf
		   accepts them, so a literal null switches to Arrays.asList, which does not. */
		boolean withNull=args.contains("null");
		if(name.equals("listOf"))
			return withNull?"Arrays.asList("+args+")":"List.of("+args+")";
		/* Kotlin's setOf and mutableSetOf keep the order the elements were written in
		   (both are LinkedHashSet). Set.of does not order them at all -- it even
		   answers a different order on each run of the same program -- and HashSet
		   orders them by hash, so either one changes what the program prints. Only
		   hashSetOf, which is unordered in Kotlin too, stays a HashSet. */
		if(name.equals("setOf")||name.equals("mutableSetOf"))
			return args.isEmpty()?"new LinkedHashSet<>()"
				:"new LinkedHashSet<>(Arrays.asList("+args+"))";
		// Kotlin's mutable builders: Java needs a real collection, not an immutable view
		if(name.equals("mutableListOf")||name.equals("arrayListOf"))
			return args.isEmpty()?"new ArrayList<>()":"new ArrayList<>(Arrays.asList("+args+"))";
		if(name.equals("hashSetOf"))
			return args.isEmpty()?"new HashSet<>()":"new HashSet<>(Arrays.asList("+args+"))";
		if(name.equals("Any"))return "new Object()";
		if(MATH.containsKey(name)&&!functions.containsKey(name)&&!classes.containsKey(name)){
			if(name.equals("log2")){
				errors.add("log2 has no java.lang.Math counterpart; use ln(x) / ln(2.0)");
				return "Math.log("+args+")";
			}
			return "Math."+MATH.get(name)+"("+args+")";
		}
		// kotlin.text.Regex is a wrapper over java.util.regex, so the stdlib type is
		// mapped to Pattern instead of being translated.
		if(name.equals("Regex")&&!classes.containsKey(name)){
			needsRegex=true;
			return "Pattern.compile("+args+")";
		}
		if(classes.containsKey(name))return "new "+javaName(classes.get(name))+"("+args+")";
		if(functions.containsKey(name)){
			Fn callee=pick(functions.get(name),argCount);
			if(!callee.captured.isEmpty()){
				String extra=String.join(", ",callee.captured.keySet());
				args=args.isEmpty()?extra:args+", "+extra;
			}
			return (callee.home==null?"":callee.home+".")+name+"("+args+")";
		}
		if(current!=null&&methodOf(current,name)!=null)return name+"("+args+")";
		// Unknown callee: Kotlin capitalises types, so treat it as a constructor.
		if(Character.isUpperCase(name.charAt(0)))return "new "+name+"("+args+")";
		return name+"("+args+")";
	}

	/* Kotlin's conversion methods. On a String they parse, on a number they cast;
	   Java has neither as a method, so the call is rewritten. */
	static final Map<String,String> PARSERS=new HashMap<String,String>();
	static final Map<String,String> CASTS=new HashMap<String,String>();
	static {
		PARSERS.put("toInt","Integer.parseInt");
		PARSERS.put("toLong","Long.parseLong");
		PARSERS.put("toDouble","Double.parseDouble");
		PARSERS.put("toFloat","Float.parseFloat");
		PARSERS.put("toBoolean","Boolean.parseBoolean");
		CASTS.put("toInt","int");
		CASTS.put("toLong","long");
		CASTS.put("toDouble","double");
		CASTS.put("toFloat","float");
		CASTS.put("toByte","byte");
		CASTS.put("toChar","char");
	}

	/** Calling a method on a receiver, shared with the chain translation. */
	String memberCall(String recvRaw,String recvType,String method,String args,
			KotlinParser.ArgListContext argList){
		String recv=group(recvRaw);
		/* Kotlin 1.5 renamed these two; Java keeps the older names. (Kotlin's use
		   Locale.ROOT and Java's the default locale, which differ only where the
		   locale changes the letters, as in Turkish.) */
		if(method.equals("uppercase"))method="toUpperCase";
		else if(method.equals("lowercase"))method="toLowerCase";
		if("String".equals(recvType)&&PARSERS.containsKey(method))
			return PARSERS.get(method)+"("+recv+")";
		if(rank(recvType)>0&&!"String".equals(recvType)&&CASTS.containsKey(method))
			return "("+CASTS.get(method)+") "+recv;
		if("Pattern".equals(recvType))return regexCall(recv,method,argParts(argList));
		if(method.equals("toRegex")&&"String".equals(recvType)){
			needsRegex=true;
			return "Pattern.compile("+recv+")";
		}
		if(rank(recvType)>0&&!"String".equals(recvType)){
			/* Kotlin's rounding TO an integer is the one that rounds a tie up, which is
			   exactly Math.round; it answers a long, hence the cast and the parens. */
			if(method.equals("roundToInt"))return "((int) Math.round("+recv+"))";
			if(method.equals("roundToLong"))return "Math.round("+recv+")";
			if(MATH.containsKey(method))
				return "Math."+MATH.get(method)+"("+recv+(args.isEmpty()?"":", "+args)+")";
		}
		if(BOXED.containsKey(recvType)){
			// Kotlin calls methods on Int/Double/...; Java cannot dereference a
			// primitive, so the value is boxed (or handed to String.valueOf).
			if(method.equals("toString")&&args.isEmpty())return "String.valueOf("+recv+")";
			return box(recvType)+".valueOf("+recv+")."+method+"("+args+")";
		}
		if(method.equals("isNotEmpty")&&args.isEmpty())return "!"+recv+".isEmpty()";
		/* Collection reads Java words differently. 'last' and 'joinToString' go through
		   a helper so the receiver is evaluated once, as Kotlin does. */
		if(elem(recvType)!=null){
			if(method.equals("first")&&args.isEmpty())return recv+".get(0)";
			if(method.equals("last")&&args.isEmpty()){
				needsLast=true;
				return "lastOf("+recv+")";
			}
			/* Kotlin names its two removals apart; Java overloads one name, and on a
			   List<Integer> the by-index overload silently hides the by-value one. */
			if(method.equals("removeAt"))return recv+".remove("+args+")";
			if(method.equals("remove")&&"Integer".equals(elem(recvType)))
				return recv+".remove(Integer.valueOf("+args+"))";
			if(method.equals("joinToString")){
				needsJoin=true;
				return "joinToString("+recv+", "+(args.isEmpty()?"\", \"":args)+")";
			}
		}
		if(method.equals("split")&&"String".equals(recvType)){
			/* Kotlin splits on a literal and answers a List; Java splits on a regex and
			   answers an array, so the delimiter is quoted and the result wrapped. The
			   -1 limit is what keeps the empty fields at the end, which Kotlin returns
			   and Java drops: "a,b,,".split(",") is four fields, not two. */
			needsRegex=true;
			return "List.of("+recv+".split(Pattern.quote("+args+"), -1))";
		}
		boolean asString="Object".equals(recvType)&&STRING_METHODS.contains(method);
		String target=asString?"((String) "+recv+")":recv;
		if(method.equals("toIntOrNull")){
			// A regex plus Integer.valueOf would still throw on values that do not fit
			// an int, while Kotlin answers null; the helper keeps both cases right.
			needsToIntOrNull=true;
			return "toIntOrNull("+(asString?"(String) "+recv:recv)+")";
		}
		if(method.equals("sum")){
			String e=elem(recvType);
			String prim=e==null?"double":primitiveOf(e);
			return recv+".stream().mapTo"+Character.toUpperCase(prim.charAt(0))+prim.substring(1)
				+"("+box(prim)+"::"+prim+"Value).sum()";
		}
		return target+"."+method+"("+args+")";
	}

	String memberCallType(String recvType,String method,KotlinParser.ArgListContext argList){
		if(elem(recvType)!=null){
			if(method.equals("get")||method.equals("first")||method.equals("last")
					||method.equals("removeAt"))return elem(recvType);
			if(method.equals("remove"))return "boolean";
			if(method.equals("joinToString"))return "String";
		}
		if(method.equals("indexOf")||method.equals("lastIndexOf")
				||method.equals("compareTo"))return "int";
		if(method.equals("charAt"))return "char";
		if(method.equals("roundToInt"))return "int";
		if(method.equals("roundToLong"))return "long";
		if(method.equals("split"))return "List<String>";
		if("Math".equals(recvType))return "double";
		if("String".equals(recvType)&&PARSERS.containsKey(method))
			return method.equals("toBoolean")?"boolean":CASTS.get(method);
		if(rank(recvType)>0&&!"String".equals(recvType)&&CASTS.containsKey(method))
			return CASTS.get(method);
		if(rank(recvType)>0&&!"String".equals(recvType)&&MATH.containsKey(method))return "double";
		if(method.equals("toRegex"))return "Pattern";
		if("Pattern".equals(recvType))return regexCallType(method);
		if(method.equals("toString"))return "String";
		if(method.equals("toIntOrNull"))return "Integer";
		if(method.equals("sum"))return primitiveOf(elem(recvType)==null?"Double":elem(recvType));
		if(method.equals("startsWith")||method.equals("endsWith")||method.equals("contains")
				||method.equals("isEmpty")||method.equals("isNotEmpty"))return "boolean";
		if(STRING_METHODS.contains(method))return "String";
		Cls k=classOf(recvType);
		Fn m=k==null?null:pickFor(methodOf(k,method),argTypes(argList));
		return m==null?null:returnTypeOf(m);
	}

	static String primitiveOf(String boxedType){
		for(Map.Entry<String,String> e:BOXED.entrySet())
			if(e.getValue().equals(boxedType))return e.getKey();
		return boxedType;
	}

	List<String> argParts(KotlinParser.ArgListContext ctx){
		List<String> out=new ArrayList<String>();
		if(ctx!=null)flattenArgs(ctx,out);
		return out;
	}

	String argsOf(KotlinParser.ArgListContext ctx){
		List<String> out=new ArrayList<String>();
		flattenArgs(ctx,out);
		return String.join(", ",out);
	}

	void flattenArgs(KotlinParser.ArgListContext ctx,List<String> out){
		for(ParseTree c:children(ctx)){
			if(c instanceof KotlinParser.ArgListContext)flattenArgs((KotlinParser.ArgListContext)c,out);
			else if(c instanceof KotlinParser.AssignexprContext)out.add(code(c));
		}
	}

	String logexpression(KotlinParser.LogexpressionContext c){
		if(c.getChildCount()==1)return code(c.getChild(0));
		String op=c.getChild(1).getText();
		ParseTree l=c.getChild(0);
		ParseTree r=c.getChild(2);
		if(op.equals("&&")){
			// everything to the right of 'x is T &&' may rely on the narrower type
			String[] cast=narrowingOf(l,false);
			String left=code(l);
			String[] saved=cast==null?null:pushNarrowing(cast[0],cast[1]);
			String right=code(r);
			popNarrowing(saved);
			return left+" && "+right;
		}
		if(op.equals("<")||op.equals(">")||op.equals("<=")||op.equals(">=")){
			// Kotlin's ordering operators on strings mean compareTo; Java rejects them
			if("String".equals(typeOf(l))&&"String".equals(typeOf(r)))
				return group(code(l))+".compareTo("+code(r)+") "+op+" 0";
		}
		/* '===' asks whether it is the same object, which is what Java's '==' answers.
		   Kotlin's '==' is structural and becomes Objects.equals just below. */
		if(op.equals("===")||op.equals("!=="))
			return code(l)+" "+op.substring(0,2)+" "+code(r);
		if(op.equals("==")||op.equals("!=")){
			boolean nullCheck=l.getText().equals("null")||r.getText().equals("null");
			if(!nullCheck&&isReference(typeOf(l))&&isReference(typeOf(r))){
				// Kotlin's == is structural. Java's == would compare references here,
				// so two boxed 1000s would come out unequal.
				return (op.equals("!=")?"!":"")+"Objects.equals("+code(l)+", "+code(r)+")";
			}
		}
		return code(l)+" "+op+" "+code(r);
	}

	/** logsides: '!' logsides | 'is' type | 'in' (...) | (expression|stringLiteral) logsides? */
	String logsides(KotlinParser.LogsidesContext c){
		String first=c.getChild(0).getText();
		if(first.equals("!")){
			KotlinParser.LogsidesContext inner=c.logsides();
			if(bareSuffixOf(inner)!=null)
				return cannotTranslate("'"+c.getText()+"' has no left operand; a test like"
					+" this only makes sense as a when branch");
			return "!("+logsides(inner)+")";
		}
		if(first.equals("is")||first.equals("in"))
			return cannotTranslate("'"+c.getText()+"' has no left operand; a test like"
				+" this only makes sense as a when branch");
		ParseTree operandNode=c.getChild(0);
		String operand=code(operandNode);
		if(c.getChildCount()==1)return operand;
		return logsidesSuffix(c.logsuffix(),operand,typeOf(operandNode),false,
			!hasCall(operandNode));
	}

	static String bareSuffixOf(KotlinParser.LogsidesContext c){
		String f=c.getChild(0).getText();
		return (f.equals("is")||f.equals("in"))?f:null;
	}

	/* Applies an 'is'/'in' test to an already translated operand. The test arrives
	   either as a bare logsides (a when branch, which has no left operand of its own)
	   or as the logsuffix of an operand, so both shapes are walked by position. */
	String logsidesSuffix(ParserRuleContext suffix,String operand,String operandType,
			boolean negated,boolean repeatable){
		int at=0;
		if(suffix.getChild(0).getText().equals("!")){
			ParseTree inner=suffix.getChild(1);
			if(inner instanceof KotlinParser.LogsidesContext)
				return logsidesSuffix((ParserRuleContext)inner,operand,operandType,
					!negated,repeatable);
			negated=!negated;
			at=1;
		}
		String first=suffix.getChild(at).getText();
		if(first.equals("is")){
			// Java rejects instanceof between unrelated types; Kotlin allows it.
			String test="((Object) "+group(operand)+") instanceof "
				+typeName(suffix.getChild(at+1).getText(),true);
			return negated?"!("+test+")":test;
		}
		if(first.equals("in")){
			ParseTree target=suffix.getChild(at+1);
			/* A range becomes two comparisons, so the value is written twice. Java has
			   nowhere to hold it inside a condition, and running a call twice is not a
			   translation, so it is refused -- as '?.' on a call result is. */
			boolean twice=target instanceof KotlinParser.ExplicitRangeContext
				||(target.getChildCount()==3&&target.getChild(2).getText().equals("indices"));
			if(twice&&!repeatable)
				return cannotTranslate("'in' over a range on the result of a call is not"
					+" supported; assign it to a variable first");
			String test;
			if(target instanceof KotlinParser.ExplicitRangeContext)
				test=rangeTest((KotlinParser.ExplicitRangeContext)target,operand);
			else{
				KotlinParser.ExprIDContext id=(KotlinParser.ExprIDContext)target;
				if(id.getChildCount()==3&&id.getChild(2).getText().equals("indices"))
					test="("+operand+" >= 0 && "+operand+" < "
						+receiverOf(id)+".size())";
				else test=code(id)+".contains("+operand+")";
			}
			return negated?"!"+test:test;
		}
		// operand followed by another operand: not something the grammar should produce
		return operand;
	}

	String rangeTest(KotlinParser.ExplicitRangeContext r,String value){
		String kind=r.getChild(1).getText();
		String from=code(r.getChild(0));
		String to=code(r.getChild(2));
		if(kind.equals("downTo"))
			return "("+value+" <= "+from+" && "+value+" >= "+to+")";
		return "("+value+" >= "+from+" && "+value
			+(kind.equals("until")?" < ":" <= ")+to+")";
	}

	/** lambdaexpression: ID lambdaTerm+ -- a Kotlin collection chain becomes a Stream. */
	String lambda(KotlinParser.LambdaexpressionContext c){ return walkLambda(c)[0]; }

	/** What the pipeline answers: void once a terminal stage consumed it. */
	String typeOfLambda(KotlinParser.LambdaexpressionContext c){ return walkLambda(c)[1]; }

	/* Code and type come out of one walk, as they do for a chain: a 'map' stage
	   changes what the elements are, and the type has to follow the same stages the
	   code does or the two would disagree. */
	String[] walkLambda(KotlinParser.LambdaexpressionContext c){
		String name=c.getChild(0).getText();
		String itType=elem(lookup(name));
		// the receiver goes through the same resolution as any other name
		StringBuilder sb=new StringBuilder(receiverOf(c)+".stream()");
		boolean terminal=false;
		for(ParseTree t:children(c)){
			if(!(t instanceof KotlinParser.LambdaTermContext))continue;
			KotlinParser.LambdaTermContext term=(KotlinParser.LambdaTermContext)t;
			String op=term.getChild(1).getText();
			// '{ x -> body }' names its parameter; '{ body }' uses the implicit 'it'
			boolean named=term.getChildCount()>5&&term.getChild(4).getText().equals("->");
			String param=named?term.getChild(3).getText():"it";
			ParseTree bodyNode=named?term.getChild(5):term.getChild(3);
			push();
			declare(param,itType);
			String body=code(bodyNode);
			// 'map' changes what the elements are, so the next stage sees the new type
			String mapped=op.equals("map")?typeOf(bodyNode):null;
			pop();
			if(mapped!=null)itType=box(mapped);
			if(op.equals("sortedBy")){
				sb.append(body.equals(param)?".sorted()"
					:".sorted(Comparator.comparing("+param+" -> "+body+"))");
				continue;
			}
			if(op.equals("forEach"))terminal=true;
			sb.append(".").append(op).append("(").append(param).append(" -> ")
				.append(body).append(")");
		}
		if(terminal)return new String[]{sb.toString(),"void"};
		needsCollectors=true;
		sb.append(".collect(Collectors.toList())");
		return new String[]{sb.toString(),itType==null?null:"List<"+box(itType)+">"};
	}

	// -------------------------------------------------------- local functions

	/* Works out which enclosing locals a local function reads, so they can be
	   passed as parameters. Assigning to one of them cannot be expressed this way,
	   so that is reported as an error instead of silently changing behaviour. */
	void liftCaptures(KotlinParser.FunctionContext ctx){
		if(ctx.abstractFunction()!=null)return;
		String name=ctx.memberName().getText();
		Fn fn=declarationFor(functions.get(name),ctx);
		if(fn==null)return;
		fn.captured.clear();
		Set<String> local=new HashSet<String>(fn.paramNames);
		collectDeclared(ctx,local);
		LinkedHashSet<String> used=new LinkedHashSet<String>();
		collectReferences(ctx,used);
		for(String id:used){
			if(local.contains(id)||!inLocalScopes(id))continue;
			fn.captured.put(id,lookup(id));
		}
		/* Calling another hoisted function means passing its captures along too, or the
		   call site would reference variables this function does not receive. */
		Set<String> callees=new HashSet<String>();
		collectCalls(ctx,callees);
		for(String callee:callees){
			Fn other=functions.get(callee);
			if(other==null||other==fn||other.captured.isEmpty())continue;
			for(Map.Entry<String,String> c:other.captured.entrySet())
				if(!local.contains(c.getKey())&&inLocalScopes(c.getKey()))
					fn.captured.put(c.getKey(),c.getValue());
		}
		Set<String> written=new HashSet<String>();
		collectAssigned(ctx,written);
		for(String id:written)
			if(fn.captured.containsKey(id))
				errors.add("local function '"+name+"' assigns to the captured variable '"
					+id+"'; Java cannot hoist that, move the variable to file scope");
	}

	boolean inLocalScopes(String name){
		for(Map<String,String> s:scopes)if(s.containsKey(name))return true;
		return false;
	}

	/** Names declared inside the body, which therefore shadow the outer ones. */
	void collectDeclared(ParseTree n,Set<String> out){
		if(n instanceof KotlinParser.NoassignContext)
			out.add(((KotlinParser.NoassignContext)n).ID().getText());
		else if(n instanceof KotlinParser.WithassignContext)
			out.add(declName((KotlinParser.WithassignContext)n));
		else if(n instanceof KotlinParser.ForStmtContext)
			out.add(((KotlinParser.ForStmtContext)n).ID().getText());
		else if(n instanceof KotlinParser.LambdaTermContext){
			out.add("it");                                  // the implicit parameter
			if(n.getChildCount()>5&&n.getChild(4).getText().equals("->"))
				out.add(n.getChild(3).getText());           // or the named one
		}
		for(ParseTree c:children(n))collectDeclared(c,out);
	}

	/* Identifiers the body reads. Member names after a '.' and callee names are
	   skipped; string templates are scanned too, since '$name' is a reference. */
	void collectReferences(ParseTree n,Set<String> out){
		if(n instanceof KotlinParser.ExprIDContext){
			out.add(n.getChild(0).getText());
			return;
		}
		if(n instanceof KotlinParser.ListIDContext){
			out.add(n.getChild(0).getText());
			String index=n.getChild(2).getText();
			if(Character.isLetter(index.charAt(0)))out.add(index);
			return;
		}
		if(n instanceof KotlinParser.StringLiteralContext){
			templateReferences(n.getText(),out);
			return;
		}
		if(n instanceof KotlinParser.CallContext&&((KotlinParser.CallContext)n).callName()==null)
			out.add(n.getChild(0).getText());
		if(n instanceof KotlinParser.ChainStartContext&&n.getChild(0) instanceof TerminalNode)
			out.add(n.getChild(0).getText());
		if(n instanceof KotlinParser.LambdaexpressionContext)out.add(n.getChild(0).getText());
		if(n instanceof KotlinParser.WhenStmtContext&&n.getChild(1).getText().equals("("))
			out.add(n.getChild(2).getText());
		for(ParseTree c:children(n))collectReferences(c,out);
	}

	static void templateReferences(String raw,Set<String> out){
		for(int i=0;i<raw.length()-1;i++){
			if(raw.charAt(i)!='$')continue;
			int j=i+1;
			if(raw.charAt(j)=='{'){
				int depth=1;
				j++;
				int start=j;
				while(j<raw.length()&&depth>0){
					if(raw.charAt(j)=='{')depth++;
					else if(raw.charAt(j)=='}')depth--;
					if(depth>0)j++;
				}
				for(String word:raw.substring(start,Math.min(j,raw.length())).split("[^A-Za-z0-9_]+"))
					if(!word.isEmpty()&&Character.isLetter(word.charAt(0)))out.add(word);
				i=j;
				continue;
			}
			int start=j;
			while(j<raw.length()&&(Character.isLetterOrDigit(raw.charAt(j))||raw.charAt(j)=='_'))j++;
			if(j>start)out.add(raw.substring(start,j));
			i=j-1;
		}
	}

	/** Names called by plain name, so their captures can be threaded through. */
	void collectCalls(ParseTree n,Set<String> out){
		if(n instanceof KotlinParser.CallContext){
			KotlinParser.CallContext c=(KotlinParser.CallContext)n;
			if(c.callName()!=null)out.add(c.callName().getText());
		}
		if(n instanceof KotlinParser.CallHeadContext)
			out.add(((KotlinParser.CallHeadContext)n).callName().getText());
		for(ParseTree c:children(n))collectCalls(c,out);
	}

	/** Names the body assigns to, including '++' and '--'. */
	void collectAssigned(ParseTree n,Set<String> out){
		if(n instanceof KotlinParser.IdentifierStmtContext){
			KotlinParser.IdentifierStmtContext c=(KotlinParser.IdentifierStmtContext)n;
			if(c.exprID()!=null)out.add(c.exprID().getChild(0).getText());
		}
		if(n instanceof KotlinParser.ExpressionContext&&n.getChildCount()==2)
			out.add(n.getChild(0).getText());
		for(ParseTree c:children(n))collectAssigned(c,out);
	}

	// ---------------------------------------------------------------- chains

	/* A member path such as 'a.b().c()', '"42".toIntOrNull()' or 's?.length'. The
	   links are walked left to right carrying the code built so far together with
	   its type, which is what lets each hop resolve against the right receiver. */
	String chain(KotlinParser.ChainContext c){ return walkChain(c)[0]; }

	String typeOfChain(KotlinParser.ChainContext c){ return walkChain(c)[1]; }

	String[] walkChain(KotlinParser.ChainContext c){
		ParseTree start=c.chainStart().getChild(0);
		String code;
		String type;
		if(start instanceof KotlinParser.CallHeadContext){
			KotlinParser.CallHeadContext h=(KotlinParser.CallHeadContext)start;
			String args=h.argList()==null?"":argsOf(h.argList());
			code=namedCall(h.callName().getText(),args,argParts(h.argList()).size());
			type=namedCallType(h.callName().getText(),h.argList());
		}else if(start instanceof KotlinParser.StringLiteralContext){
			code=template(start.getText());
			type="String";
		}else{
			String name=start.getText();
			code=identifierCode(name);
			// 'Math' is not a value, but naming it as the receiver type lets the next
			// link know the call yields a double.
			type="Math".equals(name)?"Math":lookup(name);
		}
		return chainLinks(code,type,c.chainLink(),0,
			start instanceof KotlinParser.CallHeadContext);
	}

	/* 'prefixCalls' says whether what is written to the left of this link performs a
	   call. It is carried along rather than guessed from the generated text, where a
	   parenthesis may belong to a string or to an accessor this tool introduced. */
	String[] chainLinks(String code,String type,List<KotlinParser.ChainLinkContext> links,
			int i,boolean prefixCalls){
		if(i>=links.size())return new String[]{code,type};
		KotlinParser.ChainLinkContext link=links.get(i);
		boolean safe=link.getChild(0).getText().equals("?.");
		boolean isCall=link.getChildCount()>2;
		String member=link.memberName().getText();
		String next;
		String nextType;
		if(isCall){
			String args=link.argList()==null?"":argsOf(link.argList());
			next=memberCall(code,type,member,args,link.argList());
			nextType=memberCallType(type,member,link.argList());
		}else{
			next=memberRead(code,type,member);
			nextType=memberReadType(type,member);
		}
		if(!safe)return chainLinks(next,nextType,links,i+1,prefixCalls||isCall);
		/* Java has no '?.', so it becomes a conditional. That repeats the receiver
		   code, which is fine for a name or a field read but would run a call twice,
		   so that case is refused rather than silently evaluated again. */
		if(prefixCalls)
			errors.add("safe call '?."+member+"' on the result of a call is not supported;"
				+" assign it to a variable first");
		String[] rest=chainLinks(next,nextType,links,i+1,prefixCalls||isCall);
		// '?.' on something that maps to the receiver itself needs no guard at all
		if(rest[0].equals(code))return new String[]{code,box(rest[1])};
		return new String[]{"("+code+" == null ? null : "+rest[0]+")",box(rest[1])};
	}

	// ------------------------------------------------------------ templates

	/* Turns a Kotlin string (the whole STRING token, quotes included) into a Java
	   expression: text runs stay as literals and every '$id' or '${expr}'
	   template is concatenated with '+'.
	     "sum of $a and $b is ${a+b}"  ->  "sum of " + a + " and " + b + " is " + (a+b) */
	String template(String raw){
		String body=raw.substring(1,raw.length()-1);
		List<String> parts=new ArrayList<String>();
		StringBuilder text=new StringBuilder();
		int i=0;
		while(i<body.length()){
			char ch=body.charAt(i);
			if(ch=='\\'&&i+1<body.length()){
				// '\$' is a literal '$'; every other Kotlin escape is valid in Java too
				if(body.charAt(i+1)=='$')text.append('$');
				else text.append(ch).append(body.charAt(i+1));
				i+=2;
				continue;
			}
			if(ch=='$'&&i+1<body.length()){
				String ref=null;
				if(body.charAt(i+1)=='{'){
					int depth=1;
					int j=i+2;
					while(j<body.length()&&depth>0){
						if(body.charAt(j)=='{')depth++;
						else if(body.charAt(j)=='}')depth--;
						if(depth>0)j++;
					}
					if(depth==0){
						ref="("+translateFragment(body.substring(i+2,j))+")";
						i=j+1;
					}
				}else if(Character.isLetter(body.charAt(i+1))||body.charAt(i+1)=='_'){
					int j=i+1;
					while(j<body.length()&&(Character.isLetterOrDigit(body.charAt(j))
							||body.charAt(j)=='_'))j++;
					// '$name' is a bare name and gets exactly the resolution one gets
					// anywhere else: a local shadows a getter, a dependency's global is
					// qualified, a narrowed name keeps its cast.
					ref=identifierCode(body.substring(i+1,j));
					i=j;
				}
				if(ref!=null){
					if(text.length()>0){
						parts.add("\""+text+"\"");
						text.setLength(0);
					}
					parts.add(ref);
					continue;
				}
			}
			text.append(ch);
			i++;
		}
		if(text.length()>0||parts.isEmpty())parts.add("\""+text+"\"");
		String joined=String.join(" + ",parts);
		// Java concatenates left to right, so only a pair of references at the front
		// would be added instead of joined: "$a$b" needs the empty literal, "$a b"
		// does not.
		boolean startsWithRef=!parts.get(0).startsWith("\"");
		boolean secondIsRef=parts.size()<2||!parts.get(1).startsWith("\"");
		if(startsWithRef&&secondIsRef)joined="\"\" + "+joined;
		return joined;
	}

	/** Reparses the inside of a '${...}' with the same grammar. */
	String translateFragment(String src){
		KotlinLexer lexer=new KotlinLexer(CharStreams.fromString(src));
		lexer.removeErrorListeners();
		KotlinParser parser=new KotlinParser(new CommonTokenStream(lexer));
		parser.removeErrorListeners();
		KotlinParser.ExprContext tree=parser.expr();
		/* Leftover tokens mean only a prefix was understood. Reporting it beats
		   emitting that prefix, which is how '${f(x) + 1}' silently became '(f)'. */
		if(parser.getNumberOfSyntaxErrors()>0
				||parser.getCurrentToken().getType()!=Token.EOF){
			errors.add("cannot translate the template ${"+src+"}");
			return src;
		}
		return code(tree);
	}

	// ------------------------------------------------------- type inference

	String typeOf(ParseTree n){
		if(n instanceof KotlinParser.ExprContext
				||n instanceof KotlinParser.AssignexprContext
				||n instanceof KotlinParser.ConditionContext)return typeOf(n.getChild(0));
		if(n instanceof KotlinParser.StringLiteralContext)return "String";
		if(n instanceof KotlinParser.NumContext)return numType(n.getText());
		if(n instanceof KotlinParser.LambdaexpressionContext)
			return typeOfLambda((KotlinParser.LambdaexpressionContext)n);
		// 'xs[i]' is one element of xs, whether it arrives wrapped in an expression
		// (a value being assigned) or bare (the sequence of a for)
		if(n instanceof KotlinParser.ListIDContext)
			return elem(lookup(n.getChild(0).getText()));
		if(n instanceof KotlinParser.LogexpressionContext){
			KotlinParser.LogexpressionContext c=(KotlinParser.LogexpressionContext)n;
			return c.getChildCount()==1?typeOf(c.getChild(0)):"boolean";
		}
		if(n instanceof KotlinParser.LogsidesContext){
			KotlinParser.LogsidesContext c=(KotlinParser.LogsidesContext)n;
			return c.getChildCount()==1?typeOf(c.getChild(0)):"boolean";
		}
		if(n instanceof KotlinParser.ExpressionContext){
			KotlinParser.ExpressionContext c=(KotlinParser.ExpressionContext)n;
			if(c.num()!=null)return numType(c.num().getText());
			if(c.listID()!=null)return typeOf(c.listID());
			if(c.getChildCount()==1)return typeOf(c.getChild(0));
			if(c.getChildCount()==2)
				return typeOf(c.getChild(c.getChild(0).getText().equals("-")?1:0));
			if(c.getChild(0).getText().equals("("))return typeOf(c.getChild(1));
			return promote(typeOf(c.getChild(0)),typeOf(c.getChild(2)));
		}
		if(n instanceof KotlinParser.ExprIDContext)return exprIDType((KotlinParser.ExprIDContext)n);
		if(n instanceof KotlinParser.CallContext)return callType((KotlinParser.CallContext)n);
		if(n instanceof KotlinParser.ChainContext)return typeOfChain((KotlinParser.ChainContext)n);
		if(n instanceof KotlinParser.ShortIfContext){
			KotlinParser.ShortIfContext s=(KotlinParser.ShortIfContext)n;
			if(s.getChildCount()<=5)return typeOf(s.getChild(4));
			/* Both branches, merged the same way a when's are: promote alone knows only
			   numbers and strings, not a shared ancestor of two classes. */
			return commonType(Arrays.asList(typeOf(s.getChild(4)),typeOf(s.getChild(6))));
		}
		if(n instanceof KotlinParser.RetStmtContext)
			return n.getChildCount()==1?"void":typeOf(n.getChild(1));
		if(n instanceof KotlinParser.WhenStmtContext)
			return typeOfWhen((KotlinParser.WhenStmtContext)n);
		return null;
	}

	static String numType(String text){
		/* The 'L' suffix decides on its own, in any base. A base prefix is asked about
		   next, because the 'F' ending 0x1F is a digit there, not a float suffix. */
		char last=text.charAt(text.length()-1);
		if(last=='L'||last=='l')return "long";
		if(hasBasePrefix(text))return fitsInInt(text)?"int":"long";
		if(last=='f'||last=='F')return "float";
		if(last=='d'||last=='D')return "double";
		if(hasExponent(text)||text.indexOf('.')>=0)return "double";
		return fitsInInt(text)?"int":"long";
	}

	/* True for a decimal literal written with an exponent ('1e10', '1.5E-3'), which is
	   a Double in Kotlin. Hexadecimal is excluded: there 'E' is just a digit. */
	static boolean hasExponent(String text){
		return !hasBasePrefix(text)&&text.toLowerCase().indexOf('e')>=0;
	}

	static boolean hasBasePrefix(String text){
		String t=text.replace("_","");
		return t.startsWith("0x")||t.startsWith("0X")||t.startsWith("0b")||t.startsWith("0B");
	}

	/** The value of an integer literal in any base, or null when it is not one. */
	static Long intValue(String text){
		String t=text.replace("_","");
		if(t.endsWith("L")||t.endsWith("l"))t=t.substring(0,t.length()-1);
		try{
			if(t.startsWith("0x")||t.startsWith("0X"))return Long.parseLong(t.substring(2),16);
			if(t.startsWith("0b")||t.startsWith("0B"))return Long.parseLong(t.substring(2),2);
			return Long.parseLong(t);
		}catch(NumberFormatException e){
			return null;
		}
	}

	static boolean fitsInInt(String digits){
		Long value=intValue(digits);
		return value!=null&&value.longValue()==value.intValue();
	}

	/* Java needs the 'L' suffix on a literal too large for an int, while Kotlin infers
	   Long from the magnitude alone. */
	static String numLiteral(String text){
		if(text.indexOf('.')>=0||hasExponent(text))return text;
		char last=text.charAt(text.length()-1);
		if(last=='L'||last=='l')return text;
		if(!hasBasePrefix(text)&&!Character.isDigit(last))return text;   // f/F/d/D suffix
		return fitsInInt(text)?text:text+"L";
	}

	String exprIDType(KotlinParser.ExprIDContext c){
		String name=c.getChild(0).getText();
		if(c.getChildCount()==1){
			if(name.equals("null"))return null;
			String t=lookup(name);
			if(t!=null)return t;
			// literals the grammar lexes as plain identifiers
			if(name.equals("true")||name.equals("false"))return "boolean";
			if(name.equals("PI")||name.equals("E"))return "double";
			return null;
		}
		return memberReadType(lookup(name),c.getChild(2).getText());
	}

	String callType(KotlinParser.CallContext c){
		if(c.callName()!=null)return namedCallType(c.callName().getText(),c.argList());
		String recv=c.getChild(0).getText();
		if(recv.equals("Math"))return "double";
		return memberCallType(lookup(recv),c.getChild(2).getText(),c.argList());
	}

	String namedCallType(String name,KotlinParser.ArgListContext argList){
		if(name.equals("println")||name.equals("print"))return "void";
		if(name.equals("Any"))return "Object";
		if(name.equals("Regex")&&!classes.containsKey(name))return "Pattern";
		if(MATH.containsKey(name)&&!functions.containsKey(name)&&!classes.containsKey(name)){
			// Math.abs/max/min keep the argument type; the rest answer double
			if(name.equals("abs")||name.equals("max")||name.equals("min")){
				List<String> types=argTypes(argList);
				String t=types.isEmpty()?null:types.get(0);
				for(String other:types)t=promote(t,other);
				return t==null?"double":t;
			}
			return "double";
		}
		if(name.equals("listOf")||name.equals("setOf")||name.equals("mutableListOf")
				||name.equals("arrayListOf")||name.equals("mutableSetOf")
				||name.equals("hashSetOf")){
			String e=box(commonType(argTypes(argList)));
			boolean set=name.equals("setOf")||name.equals("mutableSetOf")
				||name.equals("hashSetOf");
			return (set?"Set<":"List<")+e+">";
		}
		if(classes.containsKey(name))return javaName(classes.get(name));
		List<String> types=argTypes(argList);
		Fn fn=pickFor(functions.get(name),types);
		if(fn==null&&current!=null)fn=pickFor(methodOf(current,name),types);
		if(fn!=null)return returnTypeOf(fn);
		return Character.isUpperCase(name.charAt(0))?name:null;
	}

	/* The element type of a literal collection has to fit EVERY element: taking the
	   first one made listOf(Circle(), Square()) a List<Circle>, which javac rejects. */
	String commonType(List<String> types){
		String best=null;
		for(String t:types){
			// a null literal has no type of its own; it fits whatever the others are
			if(t==null)continue;
			best=best==null?t:mergeTypes(best,t);
		}
		return best==null?"Object":best;
	}

	String mergeTypes(String a,String b){
		if(a.equals(b))return a;
		if(rank(a)>0&&rank(b)>0)return promote(a,b);
		// the nearest ancestor of a that b also is
		for(Cls step:hierarchy(classOf(a)))
			if(assignableTo(b,javaName(step)))return javaName(step);
		return "Object";
	}

	boolean assignableTo(String sub,String target){
		for(Cls step:hierarchy(classOf(sub)))
			if(step.name.equals(target)||javaName(step).equals(target))return true;
		return false;
	}

	List<String> argTypes(KotlinParser.ArgListContext ctx){
		List<String> out=new ArrayList<String>();
		if(ctx==null)return out;
		List<ParseTree> nodes=new ArrayList<ParseTree>();
		flattenArgNodes(ctx,nodes);
		for(ParseTree n:nodes)out.add(typeOf(n));
		return out;
	}

	void flattenArgNodes(KotlinParser.ArgListContext ctx,List<ParseTree> out){
		for(ParseTree c:children(ctx)){
			if(c instanceof KotlinParser.ArgListContext)
				flattenArgNodes((KotlinParser.ArgListContext)c,out);
			else if(c instanceof KotlinParser.AssignexprContext)out.add(c);
		}
	}

	/* The type of a when used as a value has to fit every branch, not just the first:
	   the same rule the element type of a collection literal follows. */
	String typeOfWhen(KotlinParser.WhenStmtContext ctx){
		List<String> branches=new ArrayList<String>();
		for(ParseTree c:children(ctx)){
			if(!(c instanceof KotlinParser.WhenexprContext))continue;
			String t=typeOf(c.getChild(2));
			if(t!=null&&!t.equals("void"))branches.add(t);
		}
		if(branches.isEmpty())return null;
		return commonType(branches);
	}

	String typeOfFuncassign(KotlinParser.FuncassignContext ctx){
		ParseTree v=ctx.getChild(1);
		if(v instanceof KotlinParser.WhenStmtContext)
			return typeOfWhen((KotlinParser.WhenStmtContext)v);
		if(v instanceof KotlinParser.WithElseContext)return null;
		return typeOf(v);
	}

	// ---------------------------------------------------------------- utils

	static List<ParseTree> children(ParseTree n){
		List<ParseTree> out=new ArrayList<ParseTree>();
		for(int i=0;i<n.getChildCount();i++)out.add(n.getChild(i));
		return out;
	}

	/** classStmt nodes of a class body, in source order. */
	static List<ParseTree> memberNodes(KotlinParser.ClasscompoundContext ctx){
		List<ParseTree> out=new ArrayList<ParseTree>();
		for(ParseTree c:children(ctx))
			if(c instanceof KotlinParser.ClassStmtListContext)flattenMembers((ParserRuleContext)c,out);
		return out;
	}

	static void flattenMembers(ParserRuleContext list,List<ParseTree> out){
		for(ParseTree c:children(list)){
			if(c instanceof KotlinParser.ClassStmtListContext)
				flattenMembers((ParserRuleContext)c,out);
			else if(c instanceof KotlinParser.ClassStmtContext)out.add(unwrapStmt(c.getChild(0)));
		}
	}

	/* classStmt is 'stmt|getter' and a method arrives as stmt -> function, so the
	   wrapper has to come off or the member looks like a local function. */
	static ParseTree unwrapStmt(ParseTree node){
		if(node instanceof KotlinParser.StmtContext&&node.getChildCount()==1)
			return node.getChild(0);
		return node;
	}

	static List<KotlinParser.ParamIDContext> params(ParserRuleContext list){
		List<KotlinParser.ParamIDContext> out=new ArrayList<KotlinParser.ParamIDContext>();
		collectParams(list,out);
		return out;
	}

	static void collectParams(ParseTree n,List<KotlinParser.ParamIDContext> out){
		if(n instanceof KotlinParser.ParamIDContext){
			out.add((KotlinParser.ParamIDContext)n);
			return;
		}
		for(ParseTree c:children(n))collectParams(c,out);
	}

	/* An already translated operand needs parentheses before a '.' or a cast is glued
	   onto it, or the new operator would bind to its last part instead of the whole:
	   '"a" + b' with '.length()' appended must not become '"a" + b.length()'.
	   A code fragment without spaces is a single atom and needs nothing. */
	/* Whether an expression performs a call. Asked of the tree rather than of the
	   generated text, where a parenthesis inside a string literal would lie. */
	static boolean hasCall(ParseTree n){
		if(n instanceof KotlinParser.CallContext||n instanceof KotlinParser.CallHeadContext
				||n instanceof KotlinParser.LambdaexpressionContext)return true;
		for(ParseTree c:children(n))if(hasCall(c))return true;
		return false;
	}

	static String group(String code){
		if(code.indexOf(' ')<0||wrapped(code))return code;
		return "("+code+")";
	}

	/** True when the whole fragment already sits inside one pair of parentheses. */
	static boolean wrapped(String code){
		if(!code.startsWith("(")||!code.endsWith(")"))return false;
		int depth=0;
		for(int i=0;i<code.length();i++){
			char c=code.charAt(i);
			if(c=='(')depth++;
			else if(c==')'&&--depth==0)return i==code.length()-1;
		}
		return false;
	}

	/** Adds members just before the closing brace of an already generated type. */
	static String withMembers(String type,List<String> extra){
		if(extra.isEmpty())return type;
		int close=type.lastIndexOf('}');
		StringBuilder sb=new StringBuilder(type.substring(0,close));
		for(String m:extra)sb.append(indent(m)).append("\n");
		return sb.append("}").toString();
	}

	static String braces(String body){
		return body.isEmpty()?"{\n}":"{\n"+indent(body)+"\n}";
	}

	static String indent(String text){
		StringBuilder sb=new StringBuilder();
		String[] lines=text.split("\n",-1);
		for(int i=0;i<lines.length;i++){
			if(i>0)sb.append("\n");
			sb.append(lines[i].isEmpty()?"":"    "+lines[i]);
		}
		return sb.toString();
	}
}

public class Kotlin2Java{
	static void usage(){
		System.err.println("usage: java Kotlin2Java <input> [output.java]");
		System.err.println("  <input>    a .kt file, or '-' to read from stdin");
		System.err.println("  -e <code>  translate the code given as an argument");
		System.err.println("  --deps     also translate the .kt dependencies it imports");
		System.err.println("  [output]   .java file; if omitted and the input is not a");
		System.err.println("             file, the result goes to stdout");
		System.err.println("The generated class is named after the output file (or 'Main'");
		System.err.println("when writing to stdout).");
	}

	/** True when the file starts with the mark this tool writes on its own output. */
	static boolean isGenerated(File f){
		try(BufferedReader in=new BufferedReader(new InputStreamReader(
				new FileInputStream(f),StandardCharsets.UTF_8))){
			String first=in.readLine();
			return first!=null&&first.startsWith(Translator.GENERATED_MARK);
		}catch(IOException e){
			return false;
		}
	}

	/** A valid Java class name derived from the output file. */
	static String className(String file){
		String base=new File(file).getName();
		int dot=base.lastIndexOf('.');
		if(dot>0)base=base.substring(0,dot);
		StringBuilder sb=new StringBuilder();
		for(char c:base.toCharArray())sb.append(Character.isLetterOrDigit(c)?c:'_');
		if(sb.length()==0||Character.isDigit(sb.charAt(0)))sb.insert(0,'_');
		return sb.toString();
	}

	public static void main(String[] rawArgs) throws IOException{
		boolean emitDeps=false;
		List<String> list=new ArrayList<String>();
		for(String a:rawArgs){
			if(a.equals("--deps"))emitDeps=true;
			else list.add(a);
		}
		String[] args=list.toArray(new String[0]);
		if(args.length==0||args[0].equals("-h")||args[0].equals("--help")){
			usage();
			System.exit(args.length==0?1:0);
		}
		CharStream source;
		String file=null;                      // null => write to stdout
		if(args[0].equals("-e")){
			if(args.length<2){
				usage();
				System.exit(1);
			}
			source=CharStreams.fromString(args[1]);
			if(args.length>=3)file=args[2];
		}else if(args[0].equals("-")){
			source=CharStreams.fromStream(System.in);
			if(args.length>=2)file=args[1];
		}else{
			File input=new File(args[0]);
			if(!input.isFile()){
				System.err.println("input file does not exist: "+args[0]);
				System.exit(1);
			}
			try(InputStream in=new FileInputStream(input)){
				source=CharStreams.fromStream(in);
			}
			file=(args.length>=2)?args[1]:"input.java";
		}

		KotlinLexer lexer=new KotlinLexer(source);
		KotlinParser parser=new KotlinParser(new CommonTokenStream(lexer));
		Problems problems=new Problems();
		problems.attach(lexer,parser);
		KotlinParser.ProgContext tree=parser.prog();
		if(problems.count>0){
			System.err.println("the input has "+problems.count
				+" syntax error(s); no output generated");
			System.exit(1);
		}

		File baseDir=args[0].equals("-")||args[0].equals("-e")
			?new File(".")
			:new File(args[0]).getAbsoluteFile().getParentFile();
		if(file!=null){
			/* Java requires the public class to be named after the file, so a name that
			   is not a valid identifier cannot work: say so here instead of letting
			   javac complain about a mismatch later. */
			String base=new File(file).getName();
			int dot=base.lastIndexOf('.');
			String stem=dot>0?base.substring(0,dot):base;
			if(!stem.equals(className(file))){
				System.err.println("'"+stem+"' is not a valid Java class name, and the"
					+" generated class has to match the output file name");
				System.exit(1);
			}
		}
		Set<String> seen=new HashSet<String>();
		// a file that imports something which imports it back must not be translated
		// as a dependency of itself
		if(!args[0].equals("-")&&!args[0].equals("-e"))
			seen.add(new File(args[0]).getCanonicalPath());
		Translator translator=new Translator(file==null?"Main":className(file),baseDir,seen);
		if(!args[0].equals("-")&&!args[0].equals("-e"))
			translator.sourceName=new File(args[0]).getName();
		translator.emitDeps=emitDeps;            // set before collect: imports load there
		translator.collect(tree);
		String java=translator.translate(tree);
		for(String n:translator.notes)System.err.println("note: "+n);
		if(!translator.errors.isEmpty()){
			for(String e:translator.errors)System.err.println("error: "+e);
			System.err.println("no output generated");
			System.exit(1);
		}

		if(file==null){
			PrintWriter out=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8));
			for(Map.Entry<String,String> dep:translator.depSources.entrySet())
				out.print("// ===== "+dep.getKey()+".java =====\n"+dep.getValue()+"\n");
			out.print(java);
			out.flush();
		}else{
			try{
				File dir=new File(file).getAbsoluteFile().getParentFile();
				/* Checked before anything is written: the name of a dependency's file is
				   chosen by this tool, not by whoever ran it, so a file of that name that
				   somebody wrote by hand must not be replaced. */
				for(String cls:translator.depSources.keySet()){
					File out=new File(dir,cls+".java");
					if(out.isFile()&&!isGenerated(out)){
						System.err.println("error: "+out.getName()+" exists and was not"
							+" generated by this tool; move or rename it, or translate"
							+" the dependency yourself without --deps");
						System.exit(1);
					}
				}
				for(Map.Entry<String,String> dep:translator.depSources.entrySet()){
					File out=new File(dir,dep.getKey()+".java");
					try(PrintWriter w=new PrintWriter(new OutputStreamWriter(
							new FileOutputStream(out),StandardCharsets.UTF_8))){
						w.print(dep.getValue());
					}
					System.err.println("note: wrote "+out.getName());
				}
				try(PrintWriter out=new PrintWriter(new OutputStreamWriter(
						new FileOutputStream(file),StandardCharsets.UTF_8))){
					out.print(java);
				}
			}catch(IOException e){
				System.err.println("cannot write the output: "+e.getMessage());
				System.exit(1);
			}
		}
	}
}
