## [Introduction](#introduction)

Welcome reader! This is a book about scripting with Clojure and babashka. [Clojure](https://www.clojure.org) is a functional, dynamic programming language from the Lisp family which runs on the JVM. Babashka is a scripting environment made with Clojure, compiled to native with [GraalVM](https://www.graalvm.org). The primary benefits of using babashka for scripting compared to the JVM are fast startup time and low memory consumption. Babashka comes with batteries included and packs libraries like `clojure.tools.cli` for parsing command line arguments and `cheshire` for working with JSON. Moreover, it can be installed just by downloading a self-contained binary.

### [Target audience](#_target_audience)

Babashka is written for developers who are familiar with Clojure on the JVM. This book assumes familiarity with Clojure and is not a Clojure tutorial. If you aren’t that familiar with Clojure but you’re curious to learn, check out [this](https://gist.github.com/yogthos/be323be0361c589570a6da4ccc85f58f) list of beginner resources.

### [Setting expectations](#_setting_expectations)

Babashka uses [SCI](https://github.com/babashka/SCI) for interpreting Clojure. SCI implements a substantial subset of Clojure. Interpreting code is in general not as performant as executing compiled code. If your script takes more than a few seconds to run or has lots of loops, Clojure on the JVM may be a better fit, as the performance on JVM is going to outweigh its startup time penalty. Read more about the differences with Clojure [here](#differences-with-clojure).

## [Getting started](#getting_started)

### [Installation](#_installation)

Installing babashka is as simple as downloading the binary for your platform and placing it on your path. Pre-built binaries are provided on the [releases](https://github.com/borkdude/babashka/releases) page of babashka’s [Github repo](https://github.com/borkdude/babashka). Babashka is also available in various package managers like `brew` for macOS and linux and `scoop` for Windows. See [here](https://github.com/borkdude/babashka#installation) for details.

### [Building from source](#_building_from_source)

If you would rather build babashka from source, download a copy of GraalVM and set the `GRAALVM_HOME` environment variable. Also make sure you have [lein](https://leiningen.org) installed. Then run:

```
$ git clone https://github.com/borkdude/babashka --recursive
$ cd babashka
$ script/uberjar && script/compile
```

See the babashka [build.md](https://github.com/borkdude/babashka/blob/master/doc/build.md) page for details.

### [Running babashka](#_running_babashka)

The babashka executable is called `bb`. You can either provide it with a Clojure expression directly:

```
$ bb -e '(+ 1 2 3)'
6
```

or run a script:

script.clj

```
(println (+ 1 2 3))
```

```
$ bb -f script.clj
6
```

The `-e` flag is optional when the argument starts with a paren. In that case babashka will treat it automatically as an expression:

```
$ bb '(+ 1 2 3)'
6
```

Similarly, the `-f` flag is optional when the argument is a filename:

```
$ bb script.clj
6
```

Commonly, scripts have shebangs so you can invoke them with their filename only:

```
$ ./script.clj
6
```

script.clj

```
#!/usr/bin/env bb
(println (+ 1 2 3))
```

## [Usage](#usage)

Typing `bb help` from the command line will print all the available command line options which should give you a sense of the available features in babashka.

Babashka v1.13.223

Usage: bb \[svm-opts\] \[global-opts\] \[eval opts\] \[cmdline args\]
or:    bb \[svm-opts\] \[global-opts\] file \[cmdline args\]
or:    bb \[svm-opts\] \[global-opts\] task \[cmdline args\]
or:    bb \[svm-opts\] \[global-opts\] subcommand \[subcommand opts\] \[cmdline args\]

Substrate VM opts:

  -D<property>=<value>     Set a system property (e.g. -Dx.y=99).
  -Xmx<size>\[g|G|m|M|k|K\]  Set a maximum heap size (e.g. -Xmx256M to limit the heap to 256MB).
  -XX:PrintFlags=          Print all Substrate VM options.

Global opts:

  -cp, --classpath  Classpath to use. Overrides bb.edn classpath.
  --debug           Print debug information and internal stacktrace in case of exception.
  --init <file>     Load file after any preloads and prior to evaluation/subcommands.
  --config <file>   Replace bb.edn with file. Defaults to bb.edn adjacent to invoked file or bb.edn in current dir. Relative paths are resolved relative to bb.edn.
  --deps-root <dir> Treat dir as root of relative paths in config.
  --prn             Print result via clojure.core/prn
  --force-exit      Force exiting even when non-daemon threads are still running
  -Sforce           Force recalculation of the classpath (don't use the cache)
  -Sdeps            Deps data to use as the last deps file to be merged
  -f, --file <path> Run file
  --jar <path>      Run uberjar

Help:

  help, -h or -?     Print this help text.
  version            Print the current version of babashka.
  describe           Print an EDN map with information about this version of babashka.
  doc <var|ns>       Print docstring of var or namespace. Requires namespace if necessary.

Evaluation:

  -e, --eval <expr>    Evaluate an expression.
  -m, --main <ns|var>  Call the -main function from a namespace or call a fully qualified var.
  -x, --exec <var>     Call the fully qualified var. Args are parsed by babashka CLI.

REPL:

  repl                 Start REPL.
  repl --connect \[addr\] Connect to an nREPL server. Reads .nrepl-port by default.
  socket-repl  \[addr\]  Start a socket REPL. Address defaults to localhost:1666.
  nrepl-server \[addr\]  Start nREPL server. Address defaults to localhost:1667.

Tasks:

  tasks       Print list of available tasks.
  run <task>  Run task. See run --help for more details.

Clojure:

  clojure \[args...\]  Invokes clojure. Takes same args as the official clojure CLI.

Packaging:

  uberscript <file> \[eval-opt\]  Collect all required namespaces from the classpath into a single file. Accepts additional eval opts, like \`-m\`.
  uberjar    <jar>  \[eval-opt\]  Similar to uberscript but creates jar file.
  prepare                       Download deps & pods defined in bb.edn and cache their metadata. Only an optimization, this will happen on demand when needed.

In- and output flags (only to be used with -e one-liners):

  -i                 Bind \*input\* to a lazy seq of lines from stdin.
  -I                 Bind \*input\* to a lazy seq of EDN values from stdin.
  -o                 Write lines to stdout.
  -O                 Write EDN values to stdout.
  --stream           Stream over lines or EDN values from stdin. Combined with -i or -I \*input\* becomes a single value per iteration.

Tooling:

  print-deps \[--format <deps | classpath>\]: prints a deps.edn map or classpath
    with built-in deps and deps from bb.edn.

File names take precedence over subcommand names.
Remaining arguments are bound to \*command-line-args\*.
Use -- to separate script command line args from bb command line args.
When no eval opts or subcommand is provided, the implicit subcommand is repl.

### [Running a script](#_running_a_script)

Scripts may be executed from a file using `-f` or `--file`:

```
bb -f download_html.clj
```

The file may also be passed directly, without `-f`:

```
bb download_html.clj
```

Using `bb` with a shebang also works:

```
#!/usr/bin/env bb

(require '[babashka.http-client :as http])

(defn get-url [url]
  (println "Downloading url:" url)
  (http/get url))

(defn write-html [file html]
  (println "Writing file:" file)
  (spit file html))

(let [[url file] *command-line-args*]
  (when (or (empty? url) (empty? file))
    (println "Usage: <url> <file>")
    (System/exit 1))
  (write-html file (:body (get-url url))))
```

```
$ ./download_html.clj
Usage: <url> <file>

$ ./download_html.clj https://www.clojure.org /tmp/clojure.org.html
Downloading url: https://www.clojure.org
Writing file: /tmp/clojure.org.html
```

If `/usr/bin/env` doesn’t work for you, you can use the following workaround:

```
$ cat script.clj
#!/bin/sh

#_(
   "exec" "bb" "$0" hello "$@"
   )

(prn *command-line-args*)

./script.clj 1 2 3
("hello" "1" "2" "3")
```

### [Current file path](#_current_file_path)

The var `*file*` contains the full path of the file that is currently being executed:

```
$ cat example.clj
(prn *file*)

$ bb example.clj
"/Users/borkdude/example.clj"
```

### [Parsing command line arguments](#_parsing_command_line_arguments)

Command-line arguments can be retrieved using `*command-line-args*`. If you want to parse command line arguments, you can use the built-in [`babashka.cli`](https://github.com/babashka/cli) namespace:

```
(require '[babashka.cli :as cli])

(def cli-options {:port {:default 80 :coerce :long}
                  :help {:coerce :boolean}})

(prn (cli/parse-opts *command-line-args* {:spec cli-options}))
```

```
$ bb script.clj
{:port 80}
$ bb script.clj --port 1223
{:port 1223}
$ bb script.clj --help
{:port 80, :help true}
```

Note that [clojure.tools.cli](https://github.com/clojure/tools.cli) is also built-in to babashka.

### [Classpath](#_classpath)

It is recommended to use `bb.edn` to control what directories and libraries are included on babashka’s classpath. See [Project setup](#project-setup)

If you want a lower level to control babashka’s classpath, without the usage of `bb.edn` you can use the `--classpath` option that will override the classpath. Say we have a file `script/my/namespace.clj`:

```
(ns my.namespace)
(defn -main [& args]
  (apply println "Hello from my namespace!" args))
```

Now we can execute this main function with:

```
$ bb --classpath script --main my.namespace 1 2 3
Hello from my namespace! 1 2 3
```

If you have a larger script with a classic Clojure project layout like

```
$ tree -L 3
├── deps.edn
├── README
├── src
│   └── project_namespace
│       ├── main.clj
│       └── utilities.clj
└── test
    └── project_namespace
        ├── test_main.clj
        └── test_utilities.clj
```

then you can tell babashka to include both the `src` and `test` folders in the classpath and start a socket REPL by running:

```
$ bb --classpath src:test socket-repl 1666
```

If there is no `--classpath` argument, the `BABASHKA_CLASSPATH` environment variable will be used. If that variable isn’t set either, babashka will use `:deps` and `:paths` from `bb.edn`.

Also see the [babashka.classpath](#babashka_classpath) namespace which allows dynamically adding to the classpath.

The namespace [babashka.deps](#babashkadeps) integrates [tools.deps](https://github.com/clojure/tools.deps) with babashka and allows you to set the classpath using a `deps.edn` map.

### [Invoking a main function](#main-function)

A main function can be invoked with `-m` or `--main` like shown above. When given the argument `foo.bar`, the namespace `foo.bar` will be required and the function `foo.bar/-main` will be called with command line arguments as strings.

Since babashka 0.3.1 you may pass a fully qualified symbol to `-m`:

```
$ bb -m clojure.core/prn 1 2 3
"1" "2" "3"
```

so you can execute any function as a main function, as long as it accepts the number of provided arguments.

When invoking `bb` with a main function, the expression `(System/getProperty "babashka.main")` will return the name of the main function.

### [Preloads](#_preloads)

The environment variable `BABASHKA_PRELOADS` allows to define code that will be available in all subsequent usages of babashka.

```
BABASHKA_PRELOADS='(defn foo [x] (+ x 2))'
BABASHKA_PRELOADS=$BABASHKA_PRELOADS' (defn bar [x] (* x 2))'
export BABASHKA_PRELOADS
```

Note that you can concatenate multiple expressions. Now you can use these functions in babashka:

```
$ bb '(-> (foo *input*) bar)' <<< 1
6
```

You can also preload an entire file using `load-file`:

```
export BABASHKA_PRELOADS='(load-file "my_awesome_prelude.clj")'
```

Note that `*input*` is not available in preloads.

### [Running a REPL](#repl)

Babashka supports running a REPL, a socket REPL and an nREPL server.

#### [REPL](#_repl)

To start a REPL, type:

```
$ bb repl
```

The REPL uses [JLine](https://github.com/jline/jline3) for line editing and provides:

-   Persistent history across sessions (saved to `~/.bb_repl_history` by default)
    
-   Tab completion for namespaces, vars and Java classes
    
-   Inline documentation with `C-x C-d`
    
-   The arglist of the function at the cursor
    
-   Autosuggestions based on completions
    
-   Multi-line editing with automatic detection of incomplete forms
    
-   Force submit with `C-x C-m`
    

To customize the history file location, set the `BABASHKA_REPL_HISTORY` environment variable to a file path. If it points to a directory, babashka writes `bb_repl_history` inside that directory.

```
$ BABASHKA_REPL_HISTORY=/path/to/my_history bb repl
```

#### [Socket REPL](#_socket_repl)

To start a socket REPL on port `1666`:

```
$ bb socket-repl 1666
Babashka socket REPL started at localhost:1666
```

Now you can connect with your favorite socket REPL client:

```
$ rlwrap nc 127.0.0.1 1666
Babashka v0.0.14 REPL.
Use :repl/quit or :repl/exit to quit the REPL.
Clojure rocks, Bash reaches.

bb=> (+ 1 2 3)
6
bb=> :repl/quit
$
```

The `--socket-repl` option takes options similar to the `clojure.server.repl` Java property option in Clojure:

```
$ bb socket-repl '{:address "0.0.0.0" :accept clojure.core.server/repl :port 1666}'
```

Editor plugins and tools known to work with a babashka socket REPL:

-   Emacs: [inf-clojure](https://github.com/clojure-emacs/inf-clojure):
    
    To connect:
    
    `M-x inf-clojure-connect <RET> localhost <RET> 1666`
    
    Before evaluating from a Clojure buffer:
    
    `M-x inf-clojure-minor-mode`
    
-   Atom: [Chlorine](https://github.com/mauricioszabo/atom-chlorine)
    
-   Vim: [vim-iced](https://github.com/liquidz/vim-iced)
    
-   IntelliJ IDEA: [Cursive](https://cursive-ide.com/)
    
    Note: you will have to use a workaround via [tubular](https://github.com/mfikes/tubular). For more info, look [here](https://cursive-ide.com/userguide/repl.html#repl-types).
    

#### [pREPL](#_prepl)

Launching a prepl can be done as follows:

```
$ bb socket-repl '{:address "0.0.0.0" :accept clojure.core.server/io-prepl :port 1666}'
```

or programmatically:

```
$ bb -e '(clojure.core.server/io-prepl)'
(+ 1 2 3)
{:tag :ret, :val "6", :ns "user", :ms 0, :form "(+ 1 2 3)"}
```

#### [nREPL](#_nrepl)

To start an nREPL server:

```
$ bb nrepl-server 1667
```

or programmatically:

```
$ bb -e "(babashka.nrepl.server/start-server\!) nil"
Started nREPL server at 127.0.0.1:1667
```

The server binds to `127.0.0.1` by default. To accept connections from other hosts, pass `0.0.0.0` as the host:

```
$ bb nrepl-server 0.0.0.0:1667
```

`bb nrepl-server` writes the port to `.nrepl-port` and removes the file on exit.

Then connect with your favorite nREPL client:

```
$ lein repl :connect 1667
Connecting to nREPL at 127.0.0.1:1667
user=> (+ 1 2 3)
6
user=>
```

Editor plugins and tools known to work with the babashka nREPL server:

-   Emacs: [CIDER](https://docs.cider.mx/cider/platforms/babashka.html)
    
-   `lein repl :connect`
    
-   VSCode: [Calva](http://calva.io/)
    
-   Atom: [Chlorine](https://github.com/mauricioszabo/atom-chlorine)
    
-   (Neo)Vim: [vim-iced](https://github.com/liquidz/vim-iced), [conjure](https://github.com/Olical/conjure), [fireplace](https://github.com/tpope/vim-fireplace)
    

Use `bb repl --connect` to connect to an nREPL server from the terminal. The command reads the port from `.nrepl-port`:

```
$ bb repl --connect
Connected to nREPL server at 127.0.0.1:1667, nREPL 1.7.0, Clojure 1.12.6-SCI
Type :repl/help for help
user=> (+ 1 2 3)
6
```

To connect to another server, pass `host:port`, a port or `unix://path` to `bb repl --connect`.

Pass an options map to `babashka.nrepl.server/start-server!` to configure the server:

-   `:host`: the address to bind to, `127.0.0.1` by default.
    
-   `:port`: the port, `1667` by default.
    
-   `:socket`: the path of a Unix domain socket, instead of `:host` and `:port`.
    
-   `:middleware`: a vector of nREPL middleware vars, see [nrepl\_middleware.clj](https://github.com/babashka/babashka/blob/master/examples/nrepl_middleware.clj).
    

##### [Debugging the nREPL server](#_debugging_the_nrepl_server)

To debug the nREPL server from the binary you can run:

```
$ BABASHKA_DEV=true bb nrepl-server 1667
```

This will print all the incoming messages.

To debug the nREPL server from source:

```
$ git clone https://github.com/borkdude/babashka --recurse-submodules
$ cd babashka
$ BABASHKA_DEV=true clojure -A:main --nrepl-server 1667
```

#### [REPL server port](#_repl_server_port)

For the socket REPL, pREPL, or nREPL, if a randomized port is needed, 0 can be used anywhere a port argument is accepted.

### [Input and output flags](#_input_and_output_flags)

In one-liners the `*input*` value may come in handy. It contains the input read from stdin as EDN by default. If you want to read in text, use the `-i` flag, which binds `*input*` to a lazy seq of lines of text. If you want to read multiple EDN values, use the `-I` flag. The `-o` option prints the result as lines of text. The `-O` option prints the result as lines of EDN values.

| Field | Value |
| --- | --- |
|  | `*input*` is only available in the `user` namespace, designed for one-liners. For writing scripts, see [Scripts](#_scripts). |

The following table illustrates the combination of options for commands of the form

echo "{{Input}}" | bb {{Input flags}} {{Output flags}} "\*input\*"

| Input | Input flags | Output flag | `*input*` | Output |
| --- | --- | --- | --- | --- |
| `{:a 1}` `{:a 2}` |  |  | `{:a 1}` | `{:a 1}` |
| hello<br>bye | `-i` |  | `("hello" "bye")` | `("hello" "bye")` |
| hello<br>bye | `-i` | `-o` | `("hello" "bye")` | hello<br>bye |
| `{:a 1} {:a 2}` | `-I` |  | `({:a 1} {:a 2})` | `({:a 1} {:a 2})` |
| `{:a 1} {:a 2}` | `-I` | `-O` | `({:a 1} {:a 2})` | `{:a 1} {:a 2}` |

When combined with the `--stream` option, the expression is executed for each value in the input:

```
$ echo '{:a 1} {:a 2}' | bb --stream '*input*'
{:a 1}
{:a 2}
```

#### [Scripts](#_scripts)

When writing scripts instead of one-liners on the command line, it is not recommended to use `*input*`. Here is how you can rewrite to standard Clojure code.

#### [EDN input](#_edn_input)

Reading a single EDN value from stdin:

```
(ns script
 (:require [clojure.edn :as edn]))

(edn/read *in*)
```

Reading multiple EDN values from stdin (the `-I` flag):

```
(ns script
 (:require [clojure.edn :as edn]
           [clojure.java.io :as io]))

(let [reader  (java.io.PushbackReader. (io/reader *in*))]
  (take-while #(not (identical? ::eof %)) (repeatedly #(edn/read {:eof ::eof} reader))))
```

#### [Text input](#_text_input)

Reading text from stdin can be done with `(slurp *in*)`. To get a lazy seq of lines (the `-i` flag), you can use:

```
(ns script
 (:require [clojure.java.io :as io]))

(line-seq (io/reader *in*))
```

#### [Output](#_output)

To print to stdout, use `println` for text and `prn` for EDN values.

### [Uberscript](#_uberscript)

The `--uberscript` option collects the expressions in `BABASHKA_PRELOADS`, the command line expression or file, the main entrypoint and all required namespaces from the classpath into a single file. This can be convenient for debugging and deployment.

Here is an example that uses a function from the [clj-commons/fs](https://github.com/clj-commons/fs) library.

Let’s first set the classpath:

```
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')
```

Write a little script, say `glob.clj`:

```
(ns glob (:require [me.raynes.fs :as fs]))

(run! (comp println str)
      (fs/glob (first *command-line-args*)))
```

For testing, we’ll make a file which we will find using the glob function:

```
$ touch README.md
```

Now we can execute the script which uses the library:

```
$ time bb glob.clj '*.md'
/private/tmp/glob/README.md
bb glob.clj '*.md'   0.03s  user 0.01s system 88% cpu 0.047 total
```

Producing an uberscript with all required code:

```
$ bb uberscript glob-uberscript.clj glob.clj
```

To prove that we don’t need the classpath anymore:

```
$ unset BABASHKA_CLASSPATH
$ time bb glob-uberscript.clj '*.md'
/private/tmp/glob/README.md
bb glob-uberscript.clj '*.md'   0.03s  user 0.02s system 93% cpu 0.049 total
```

Caveats:

-   _Dynamic requires_. Building uberscripts works by running top-level `ns` and `require` forms. The rest of the code is not evaluated. Code that relies on dynamic requires may not work in an uberscript.
    
-   _Resources_. The usage of `io/resource` assumes a classpath, so when this is used in your uberscript, you still have to set a classpath and bring the resources along.
    

If any of the above is problematic for your project, using an [uberjar](#uberjar) is a good alternative.

#### [Carve](#_carve)

Uberscripts can be optimized by cutting out unused vars with [carve](https://github.com/borkdude/carve).

```
$ wc -l glob-uberscript.clj
     583 glob-uberscript.clj
$ carve --opts '{:paths ["glob-uberscript.clj"] :aggressive true :silent true}'
$ wc -l glob-uberscript.clj
     105 glob-uberscript.clj
```

Note that the uberscript became 72% shorter. This has a beneficial effect on execution time:

```
$ time bb glob-uberscript.clj '*.md'
/private/tmp/glob/README.md
bb glob-uberscript.clj '*.md'   0.02s  user 0.01s system 84% cpu 0.034 total
```

### [Uberjar](#_uberjar)

Babashka can create uberjars from a given classpath and optionally a main method:

```
$ cat bb/foo.clj
(ns foo)
(defn -main [& args] (prn :hello))
$ cat bb.edn
{:paths ["bb"]}
$ bb uberjar foo.jar -m foo
$ bb foo.jar
:hello
```

### [System properties](#_system_properties)

Babashka sets the following system properties:

-   `babashka.version`: the version string, e.g. `"1.2.0"`
    
-   `babashka.main`: the `--main` argument
    
-   `babashka.file`: the `--file` argument (normalized using `.getAbsolutePath`)
    
-   `babashka.config`: the absolute path to the `bb.edn` (or other config) file
    

### [Data readers](#_data_readers)

Data readers can be enabled by setting `*data-readers*` to a hashmap of symbols to functions or vars:

```
$ bb -e "(set! *data-readers* {'t/tag inc}) #t/tag 1"
2
```

To preserve good startup time, babashka does not scan the classpath for `data_readers.clj` files.

### [Reader conditionals](#_reader_conditionals)

Babashka supports reader conditionals by taking either the `:bb` or `:clj` branch, whichever comes first. NOTE: the `:clj` branch behavior was added in version 0.0.71, before that version the `:clj` branch was ignored.

```
$ bb -e "#?(:bb :hello :clj :bye)"
:hello

$ bb -e "#?(:clj :bye :bb :hello)"
:bye

$ bb -e "[1 2 #?@(:bb [] :clj [1])]"
[1 2]
```

### [Invoking clojure](#_invoking_clojure)

Babashka bundles [deps.clj](https://github.com/borkdude/deps.clj) for invoking a `clojure` JVM process:

```
$ bb clojure -M -e "*clojure-version*"
{:major 1, :minor 10, :incremental 1, :qualifier nil}
```

See the [clojure](#_clojure) function in the [babashka.deps](#babashkadeps) namespace for programmatically invoking clojure.

## [Project setup](#project-setup)

### [bb.edn](#_bb_edn)

Since version 0.3.1, babashka supports a local `bb.edn` file to manage a project.

### [:paths and :deps](#_paths_and_deps)

You can declare one or multiple paths and dependencies so they are automatically added to the classpath:

```
{:paths ["bb"]
 :deps {medley/medley {:mvn/version "1.3.0"}}}
```

If we have a project that has a `deps.edn` and would like to reuse those deps in `bb.edn`:

```
{:deps {your-org/your-project {:local/root "."}}}
```

`bb.edn` applies to the local project, and dependencies defined in this files are never shared with other projects. This is typically what you want when writing a script or tool. By contrast, `deps.edn` is useful when creating libraries that are used by other projects.

| Field | Value |
| --- | --- |
|  | Use a unique name to refer to your project’s `deps.edn`, the same name that you would otherwise use when referring to your project as a dependency. |

| Field | Value |
| --- | --- |
|  | Only pure Clojure libraries are supported in the `bb.edn` `:deps` map. Java libraries cannot be included due to the closed nature of the GraalVM classpath. |

If we have a main function in a file called `bb/my_project/main.clj` like:

(ns my-project.main
  (:require \[medley.core :as m\]))

(defn -main \[& \_args\]
  (prn (m/index-by :id \[{:id 1} {:id 2}\])))

we can invoke it like:

```
$ bb -m my-project.main
{1 {:id 1}, 2 {:id 2}}
```

See [Invoking a main function](#main-function) for more details on how to invoke a function from the command line.

### [Dependency resolution](#deps-resolution)

Babashka resolves and downloads dependencies without a Java installation by default.

Set `:deps-resolver :jvm` in `bb.edn` or `BABASHKA_DEPS_RESOLVER=jvm` in the shell environment to resolve dependencies with `java`:

```
{:deps-resolver :jvm
 :deps {medley/medley {:mvn/version "1.3.0"}}}
```

For `add-deps`, set `:deps-resolver` in the dependency map.

With the default (non-Java) dependency resolver, S3 repositories are not (yet) supported, so use the JVM resolver instead.

### [:min-bb-version](#_min_bb_version)

Since version 0.3.6, babashka supports the `:min-bb-version` where the minimal babashka version can be declared:

```
{:paths ["src"]
 :deps {medley/medley {:mvn/version "1.3.0"}}
 :min-bb-version "0.3.7"}
```

When using an older bb version (that supports `:min-bb-version`), babashka will print a warning:

```
WARNING: this project requires babashka 0.3.7 or newer, but you have: 0.3.6
```

### [:tasks](#_tasks)

Since babashka 0.4.0 the `bb.edn` file supports the `:tasks` entry which describes tasks that you can run in the current project. The tasks feature is similar to what people use `Makefile`, `Justfile` or `npm run` for. See [Task runner](#tasks) for more details.

### [Script-adjacent bb.edn](#_script_adjacent_bb_edn)

Since babashka 1.3.177 a `bb.edn` file relative to the invoked file is respected. This makes writing system-global scripts with dependencies easier.

Given a `bb.edn`:

```
{:deps {medley/medley {:mvn/version "1.3.0"}}}
```

and a script `medley.bb`:

```
#!/usr/bin/env bb

(ns medley
  (:require [medley.core :as medley]))

(prn (medley/index-by :id [{:id 1}]))
```

Assuming that `medley.bb` is executable (`chmod +x medley.bb`), you can directly execute it in the current directory:

```
~/my_project $ ./medley.bb
{1 {:id 1}}
```

To execute this script from anywhere on the system, you just have to add it to the `PATH`:

```
/tmp $ export PATH=$PATH:~/my_project # ensure script is on path
/tmp $ medley.bb # works, respects ~/my_project/bb.edn file with :deps
{1 {:id 1}}
```

Of course you can just call your script `medley` without the `.bb` extension.

#### [Windows](#script-adjacent-bb-edn-windows)

On Windows bash shebangs are not supported. An alternative is to create a script-adjacent `.bat` file, e.g `medley.bat`:

```
@echo off
set ARGS=%*
set SCRIPT=%~dp0medley.bb
bb %SCRIPT% %ARGS%
```

Then add this script to your `%PATH%`:

```
C:\Temp> set PATH=%PATH%;c:\my_project
C:\Temp> medley
{1 {:id 1}}
```

## [Task runner](#tasks)

### [Introduction](#_introduction)

People often use a `Makefile`, `Justfile`, `npm scripts` or `lein` aliases in their (clojure) projects to remember complex invocations and to create shortcuts for them. Since version 0.4.0, babashka supports a similar feature as part of the `bb.edn` project configuration file. For a general overview of what’s available in `bb.edn`, go to [Project setup](#project-setup).

The tasks configuration lives under the `:tasks` key and can be used together with `:paths` and `:deps`:

```
{:paths ["script"]
 :deps {medley/medley {:mvn/version "1.3.0"}}
 :min-bb-version "0.4.0"
 :tasks
 {clean (shell "rm -rf target")
 ...}
 }
```

In the above example we see a simple task called `clean` which invokes the `shell` command, to remove the `target` directory. You can invoke this task from the command line with:

```
$ bb run clean
```

Babashka also accepts a task name without explicitly mentioning `run`:

```
$ bb clean
```

To make your tasks more cross-platform friendly, you can use the built-in [babashka.fs](https://github.com/babashka/fs) library. To use libraries in tasks, use the `:requires` option:

```
{:tasks
 {:requires ([babashka.fs :as fs])
  clean (fs/delete-tree "target")
  }
 }
```

Tasks accept arbitrary Clojure expressions. E.g. you can print something when executing the task:

```
{:tasks
 {:requires ([babashka.fs :as fs])
  clean (do (println "Removing target folder.")
            (fs/delete-tree "target"))
  }
 }
```

```
$ bb clean
Removing target folder.
```

### [Talk](#_talk)

Go [here](https://www.youtube.com/watch?v=u5ECoR7KT1Y&ab_channel=LondonClojurians) if you would like to watch a talk on babashka tasks.

### [Task-local options](#_task_local_options)

Instead of naked expressions, tasks can be defined as maps with options. The task expression should then be moved to the `:task` key:

```
{:tasks
 {
  clean {:doc "Removes target folder"
         :requires ([babashka.fs :as fs])
         :task (fs/delete-tree "target")}
  }
 }
```

Tasks support the `:doc` option which gives it a docstring which is printed when invoking `bb tasks` on the command line. Other options include:

-   `:requires`: task-specific namespace requires.
    
-   `:extra-paths`: add paths to the classpath.
    
-   `:extra-deps`: add extra dependencies to the classpath.
    
-   `:enter`, `:leave`: override the global `:enter`/`:leave` hook.
    
-   `:override-builtin`: override the name of a built-in babashka command.
    
-   `:exec-fn`, `:cmd`, `:cli`, `:exec-args`: see [Command line interface](#tasks-cli).
    

### [Discoverability](#_discoverability)

When invoking `bb tasks`, babashka prints a list of all tasks found in `bb.edn` in the order of appearance. E.g. in the [clj-kondo.lsp](https://github.com/clj-kondo/clj-kondo.lsp) project it prints:

```
$ bb tasks
The following tasks are available:

recent-clj-kondo   Detects most recent clj-kondo version from clojars
update-project-clj Updates project.clj with most recent clj-kondo version
java1.8            Asserts that we are using java 1.8
build-server       Produces lsp server standalone jar
lsp-jar            Copies renamed jar for upload to clj-kondo repo
upload-jar         Uploads standalone lsp server jar to clj-kondo repo
vscode-server      Copied lsp server jar to vscode extension
vscode-version     Prepares package.json with up to date clj-kondo version
vscode-publish     Publishes vscode extension to marketplace
ovsx-publish       Publishes vscode extension to ovsx thing
publish            The mother of all tasks: publishes everything needed for new release
```

### [Command line arguments](#_command_line_arguments)

Command line arguments are available as `*command-line-args*`, just like in Clojure. Since version `0.9.160`, you can use [babashka.cli](https://github.com/babashka/cli) in tasks via the [exec](#cli:exec) function to deal with command line arguments in a concise way. See the chapter on [babashka CLI](#cli). Use [task CLI options](#tasks-cli) to add `--help` and shell completion.

Of course, you are free to parse command line arguments using the built-in `tools.cli` library or just handle them manually.

You can re-bind `*command-line-args*` to ensure functions see a different set of arguments:

```
{:tasks
 {:init (do (defn print-args []
              (prn (:name (current-task))
                   *command-line-args*)))
  bar (print-args)
  foo (do (print-args)
          (binding [*command-line-args* (next *command-line-args*)]
            (run 'bar)))}}
```

```
$ bb foo 1 2 3
foo ("1" "2" "3")
bar ("2" "3")
```

### [Command line interface](#tasks-cli)

Use `:exec-fn` or `:cmd` to configure a task with [babashka.cli](https://github.com/babashka/cli). Tasks with these keys support automatic `--help`, shell completion and subcommands.

#### [:exec-fn](#_exec_fn)

Use `:exec-fn` with a fully qualified symbol to refer to a function that handles the task:

```
{:paths ["bb"]
 :tasks {dev {:exec-fn tasks/dev}}}
```

The function receives the parsed options as a map. Declare the options in the `:org.babashka/cli` metadata of the function:

```
(ns tasks)

(defn dev
  "Start the dev system"
  {:org.babashka/cli {:spec {:port {:coerce :int
                                    :default 8080
                                    :desc "HTTP port"}}}}
  [opts]
  (prn opts))
```

```
$ bb dev --port 3000
{:port 3000}

$ bb dev --help
Usage: bb dev [options]

Start the dev system

Options:
      --port  HTTP port (default: 8080)
  -h, --help  Show this help
```

`bb tasks` uses the function docstring as the task description. If the task has a `:doc` entry, that entry overrides the docstring.

Use `:exec-args` to set default arguments for the task function:

```
{:tasks {compile {:exec-fn tasks/compile
                  :exec-args {:target "classes"}}}}
```

#### [:cmd](#_cmd)

Use `:cmd` to add subcommands to a task:

```
{:paths ["bb"]
 :tasks {db {:doc "Manage the database"
             :cmd {"migrate" {:exec-fn tasks/db-migrate}
                   "seed"    {:exec-fn tasks/db-seed}}}}}
```

```
$ bb db --help
Usage: bb db [options] <command>

Manage the database

Commands:
  migrate  Run pending migrations
  seed     Seed the database

Options:
  -h, --help  Show this help

Run "bb db <command> --help" for more information on a command.
```

Add `:exec-fn` to the task map to handle `bb db` without a subcommand.

Use a vector of command-name and option-map pairs to preserve the order of subcommands:

```
{:tasks {deploy {:cmd [["lock" {:exec-fn tasks/lock}]
                       ["unlock" {:exec-fn tasks/unlock}]]}}}
```

Set `:cmd` to a fully qualified symbol to read the command tree from a var:

```
{:tasks {db {:doc "Manage the database"
             :cmd tasks/db-cmds}}}
```

```
(def db-cmds
  {"migrate" {:exec-fn 'tasks/db-migrate}
   "seed"    {:exec-fn 'tasks/db-seed}})
```

#### [:cli](#_cli)

Use `:cli` to set babashka.cli options for the task, such as `:epilog`:

```
{:tasks {db {:doc "Manage the database"
             :cli {:epilog "Migrations are in resources/migrations."}
             :cmd {"migrate" {:exec-fn tasks/db-migrate}}}}}
```

For function-valued options such as `:error-fn`, use a var that holds the `:cli` map. Set `:cli` to the var symbol:

```
{:tasks {db {:cli tasks/db-cli
             :cmd {"migrate" {:exec-fn tasks/db-migrate}}}}}
```

Set `:cli` in the `:tasks` map to apply options to every task with `:exec-fn` or `:cmd`.

#### [:doc](#_doc)

Write `:doc` as a vector of lines for a multi-line description. `bb tasks` prints the first line.

```
{:tasks {db {:doc ["Manage the database"
                   "Migrations run in order."]
             :cmd {"migrate" {:exec-fn tasks/db-migrate}}}}}
```

#### [:depends](#_depends)

Use `:depends` to run another task first. The calling task also accepts options from dependencies with `:exec-fn`. The `--help` output lists these options under `Inherited options`:

```
{:tasks {compile {:exec-fn tasks/compile}
         jar     {:exec-fn tasks/jar
                  :depends [compile]}}}
```

```
$ bb jar --help
Usage: bb jar [options]

Build a jar

Options:
      --version  Jar version (default: 0.1.0)
  -h, --help     Show this help

Inherited options:
  --verbose  Print each file
```

The `tasks/compile` function receives all parsed options. Set `:restrict true` in its `:org.babashka/cli` metadata to limit the map to options that the function declares.

`bb jar --help` does not run dependency tasks.

#### [Shell completion](#_shell_completion)

Add this command after `compinit` in `~/.zshrc` to complete task names and options:

```
source <(bb org.babashka.cli/completions snippet --shell zsh)
```

For bash, fish, PowerShell and Nushell, see [Completions](https://github.com/babashka/cli#completions) in the babashka.cli README.

### [Run](#_run)

You can execute tasks using `bb <task-name>`. The babashka `run` subcommand can be used to execute with some additional options:

-   `--parallel`: invoke task dependencies in parallel.
    
    ```
    {:tasks
     {:init (def log (Object.))
      :enter (locking log
               (println (str (:name (current-task))
                             ":")
                        (java.util.Date.)))
      a (Thread/sleep 5000)
      b (Thread/sleep 5000)
      c {:depends [a b]}
      d {:task (time (run 'c))}}}
    ```
    
    ```
    $ bb run --parallel d
    d: #inst "2021-05-08T14:14:56.322-00:00"
    a: #inst "2021-05-08T14:14:56.357-00:00"
    b: #inst "2021-05-08T14:14:56.360-00:00"
    c: #inst "2021-05-08T14:15:01.366-00:00"
    "Elapsed time: 5023.894512 msecs"
    ```
    
    Also see [Parallel tasks](#parallel).
    
-   `--prn`: print the result from the task expression:
    
    ```
    {:tasks {sum (+ 1 2 3)}}
    ```
    
    ```
    $ bb run --prn sum
    6
    ```
    
    Unlike scripts, babashka tasks do not print their return value.
    

### [Hooks](#_hooks)

The task runner exposes the following hooks:

#### [:init](#_init)

The `:init` is for expressions that are executed before any of the tasks are executed. It is typically used for defining helper functions and constants:

```
{:tasks
 {:init (defn env [s] (System/getenv s))
  print-env (println (env (first *command-line-args*)))
  }
 }
```

```
$ FOO=1 bb print-env FOO
1
```

#### [:enter, :leave](#_enter_leave)

The `:enter` hook is executed before each task. This is typically used to print the name of a task, which can be obtained using the `current-task` function:

```
{:tasks
 {:init (defn env [s] (System/getenv s))
  :enter (println "Entering:" (:name (current-task)))
  print-env (println (env (first *command-line-args*)))
  }
 }
```

```
$ FOO=1 bb print-env FOO
Entering: print-env
1
```

The `:leave` hook is similar to `:enter` but is executed after each task.

Both hooks can be overriden as task-local options. Setting them to `nil` will disable them for specific tasks (see [Task-local options](#_task_local_options)).

### [Tasks API](#_tasks_api)

The `babashka.tasks` namespace exposes the following functions: `run`, `shell`, `clojure` and `current-task`. They are implicitly imported, thus available without a namespace prefix.

#### [run](#_run_2)

Tasks provide the `run` function to explicitly invoke another task:

```
{:tasks
 {:requires ([babashka.fs :as fs])

  clean (do
          (println "Removing target folder.")
          (fs/delete-tree "target"))
  uberjar (do
            (println "Making uberjar")
            (clojure "-X:uberjar"))
  uberjar:clean (do (run 'clean)
                    (run 'uberjar))}
 }
```

When running `bb uberjar:clean`, first the `clean` task is executed and the `uberjar`:

```
$ bb uberjar:clean
Removing target folder.
Making uberjar
```

The `clojure` function in the above example executes a clojure process using [deps.clj](https://github.com/borkdude/deps.clj). See [clojure](#tasks:clojure) for more info

The `run` function accepts an additional map with options:

##### [:parallel](#_parallel)

The `:parallel` option executes dependencies of the invoked task in parallel (when possible). See [Parallel tasks](#parallel).

#### [shell](#_shell)

Both `shell` and `clojure` return a [process](https://github.com/babashka/babashka.process) object which returns the `:exit` code among other info. By default these functions will throw an exception when a non-zero exit code was returned and they will inherit the stdin/stdout/stderr from the babashka process.

```
{:tasks
 {
  ls (shell "ls foo")
 }
}
```

```
$ bb ls
ls: foo: No such file or directory
Error while executing task: ls
$ echo $?
1
```

You can opt out of this behavior by using the `:continue` option:

```
{:tasks
 {
  ls (shell {:continue true} "ls foo")
 }
}
```

```
$ bb ls
ls: foo: No such file or directory
$ echo $?
0
```

When you want to redirect output to a file instead, you can provide the `:out` option.

```
(shell {:out "file.txt"} "echo hello")
```

To capture output as a string, set `:out` to `:string` and get the `:out` key from the resulting map. In most cases, you probably want to `trim` away the trailing newline as well:

```
(->> "echo hello" (shell {:out :string}) :out clojure.string/trim)
```

To run a program in another directory, you can use the `:dir` option:

```
(shell {:dir "subproject"} "ls")
```

To set environment variables with `shell` or `clojure`:

```
(shell {:extra-env {"FOO" "BAR"}} "printenv FOO")
```

Other supported options are similar to those of [`babashka.process/process`](https://github.com/babashka/babashka.process).

The process is executed synchronously: i.e. babashka will wait for the process to finish before executing the next expression. If this doesn’t fit your use case, you can use [`babashka.process/process`](https://github.com/babashka/babashka.process) directly instead. These two invocations are roughly equivalent:

```
(require '[babashka.process :as p :refer [process]]
         '[babashka.tasks :as tasks])

(tasks/shell {:dir "subproject"} "npm install")

(-> (process {:dir "subproject" :inherit true} "npm install")
    (p/check))
```

Note that the first string argument to `shell` is tokenized (broken into multiple parts) and the trailing arguments are not:

Correct:

```
(shell "npm install" "-g" "nbb")
```

Not correct (`-g nbb` within the same string):

```
(shell "npm install" "-g nbb")
```

Note that the varargs signature plays well with feeding `*command-line-args*`:

```
(apply shell "npm install" *command-line-args*)
```

Note that `shell` does not invoke a shell but just shells out to an external program. As such, `shell` does not understand bash specific syntax. The following does not work: `(shell "rm -rf target/*")`. To invoke a specific shell, you should do that explicitly with:

```
(shell "bash -c" "rm -rf target/*")
```

Also see the docstring of `shell` [here](https://github.com/babashka/process/blob/master/API.md#shell).

#### [clojure](#tasks:clojure)

The `clojure` function starts a Clojure process using [deps.clj](https://github.com/borkdude/deps.clj). The interface is exactly the same as the clojure CLI. E.g. to evaluate an expression:

```
{:tasks {eval (clojure "-M -e '(+ 1 2 3)'")}}
```

or to invoke clj-kondo as a library:

```
{:tasks {eval (clojure {:dir "subproject"} "-M:clj-kondo")}}
```

The `clojure` task function behaves similar to `shell` with respect to the exit code, return value and supported options, except when it comes to features that do not start a process, but only do some printing. E.g. you can capture the classpath using:

```
(with-out-str (clojure "-Spath"))
```

because this operation doesn’t start a process but prints to `*out*`.

To run a `clojure` task in another directory:

```
{:tasks {eval (clojure {:dir "subproject"} "-M:clj-kondo")}}
```

#### [current-task](#current-task)

The `current-task` function returns a map representing the currently running task. This function is typically used in the `:enter` and `:leave` hooks.

#### [exec](#_exec)

See [exec](#cli:exec).

### [Dependencies between tasks](#_dependencies_between_tasks)

Dependencies between tasks can be declared using `:depends`:

```
{:tasks {:requires ([babashka.fs :as fs])
         -target-dir "target"
         -target {:depends [-target-dir]
                  :task (fs/create-dirs -target-dir)}
         -jar-file {:depends [-target]
                    :task "target/foo.jar"}

         jar {:depends [-target -jar-file]
              :task (when (seq (fs/modified-since -jar-file
                                             (fs/glob "src" "**.clj")))
                      (spit -jar-file "test")
                      (println "made jar!"))}
         uberjar {:depends [jar]
                  :task (println "creating uberjar!")}}}
```

The `fs/modified-since` function returns a seq of all newer files compared to a target, which can be used to prevent rebuilding artifacts when not necessary.

Alternatively you can use the `:init` hook to define vars, require namespaces, etc.:

```
{:tasks {:requires ([babashka.fs :as fs])
         :init (do (def target-dir  "target")
                   (def jar-file "target/foo.jar"))
         -target {:task (fs/create-dirs target-dir)}
         jar {:depends [-target]
              :task (when (seq (fs/modified-since jar-file
                                             (fs/glob "src" "**.clj")))
                      (spit jar-file "test")
                      (println "made jar!"))}
         uberjar {:depends [jar]
                  :task (println "creating uberjar!")}}}
```

It is common to define tasks that only serve as a helper to other tasks. To not expose these tasks in the output of `bb tasks`, you can start their name with a hyphen.

### [Parallel tasks](#parallel)

The `:parallel` option executes dependencies of the invoked task in parallel (when possible). This can be used to speed up execution, but also to have multiple tasks running in parallel for development:

```
dev         {:doc  "Runs app in dev mode. Compiles cljs, less and runs JVM app in parallel."
             :task (run '-dev {:parallel true})}       (1)
-dev        {:depends [dev:cljs dev:less dev:backend]} (2)
dev:cljs    {:doc  "Runs front-end compilation"
             :task (clojure "-M:frontend:cljs/dev")}
dev:less    {:doc  "Compiles less"
             :task (clojure "-M:frontend:less/dev")}
dev:backend {:doc  "Runs backend in dev mode"
             :task (clojure (str "-A:backend:backend/dev:" platform-alias)
                            "-X" "dre.standalone/start")}
```

| Field | Value |
| --- | --- |
| **1** | The `dev` task invokes the (private) `-dev` task in parallel |
| **2** | The `-dev` task depends on three other tasks which are executed simultaneously. |

### [Invoking a main function](#_invoking_a_main_function)

Invoking a main function can be done by providing a fully qualified symbol:

```
{:tasks
  {foo-bar foo.bar/-main}}
```

You can use any fully qualified symbol, not just ones that end in `-main` (so e.g. `foo.bar/baz` is fine). You can also have multiple main functions in one namespace.

The namespace `foo.bar` will be automatically required and the function will be invoked with `*command-line-args*`:

```
$ bb foo-bar 1 2 3
```

### [REPL](#_repl_2)

To get a REPL within a task, you can use `clojure.main/repl`:

```
{:tasks {repl (clojure.main/repl)}}
```

Alternatively, you can use `babashka.tasks/run` to invoke a task from a REPL.

For REPL- and linting-friendliness, it’s recommended to move task code longer than a couple of lines to a `.clj` or `.bb` file.

### [Real world examples](#_real_world_examples)

-   [antq](https://github.com/borkdude/antq/blob/bb-run/bb.edn)
    
-   [mach](https://github.com/borkdude/mach/blob/bb-run/examples/app/bb.edn)
    
-   [bb.edn at Doctor Evidence](https://gist.github.com/borkdude/35bc0a20bd4c112dec2c5645f67250e3)
    
-   [clj-kondo.lsp](https://github.com/clj-kondo/clj-kondo.lsp/blob/master/bb.edn)
    
-   [pathom](https://github.com/wilkerlucio/pathom-viz/blob/master/bb.edn)
    
-   [rssyslib](https://github.com/redstarssystems/rssyslib/blob/develop/bb.edn)
    
-   [rewrite-clj](https://github.com/clj-commons/rewrite-clj/blob/main/bb.edn)
    
-   [https://gist.github.com/delyada/9f50fa7466358e55f27e4e6b4314242f](https://gist.github.com/delyada/9f50fa7466358e55f27e4e6b4314242f)
    
-   [jirazzz](https://github.com/rwstauner/jirazzz/blob/main/bb.edn)
    

### [Naming](#_naming)

#### [Valid names](#_valid_names)

When running a task, babashka assembles a small program which defines vars bound to the return values of tasks. This brings the limitation that you can only choose names for your tasks that are valid as var names. You can’t name your task `foo/bar` for this reason. If you want to use delimiters to indicate some sort of grouping, you can do it like `foo-bar`, `foo:bar` or `foo_bar`.

Names starting with a `-` are considered "private" and not listed in the `bb tasks` output.

#### [Conflicting file / task / subcommand names](#_conflicting_file_task_subcommand_names)

`bb <option>` is resolved in the order of file > task > subcommand.

Escape hatches in case of conflicts:

-   execute relative file as `bb ./foo`
    
-   execute task as `bb run foo`
    
-   execute subcommand as `bb --foo`
    

When choosing a task name that overrides a babashka builtin subcommand, you have to provide the `:override-builtin` option to get rid of the warning that appears when running babashka:

```
$ bb -Sdeps '{:tasks {help {:task (prn :help)}}}' help
[babashka] WARNING: task(s) 'help' override built-in command(s).
:help
```

```
$ bb -Sdeps '{:tasks {help {:task (prn :help) :override-builtin true}}}' help
:help
```

#### [Conflicting task and clojure.core var names](#_conflicting_task_and_clojure_core_var_names)

You can name a task similar to a core var, let’s say: `format`. If you want to refer to the core var, it is recommended to use the fully qualified `clojure.core/format` in that case, to avoid conflicts in `:enter` and `:leave` expressions and when using the `format` task as a dependency.

### [Syntax](#_syntax)

Because `bb.edn` is an EDN file, you cannot use all of Clojure’s syntax in expressions. Most notably:

-   You cannot use `#(foo %)`, but you can use `(fn [x] (foo x))`
    
-   You cannot use `@(foo)` but you can use `(deref foo)`
    
-   You cannot use `#"re"` but you can use `(re-pattern "re")`
    
-   Single quotes are accidentally supported in some places, but are better avoided: `{:task '(foo)}` does not work, but `{:task (quote (foo))` does work. When requiring namespaces, use the `:requires` feature in favor of doing it manually using `(require '[foo])`.
    

## [Babashka CLI](#cli)

In version `0.9.160` of babashka, the [babashka CLI](https://github.com/babashka/cli) added as a built-in library together with task integration.

### [\-x](#_x)

For invoking functions from the command line, you can use the new `-x` flag (a pun to Clojure’s `-X` of course!):

```
bb -x clojure.core/prn --hello there
{:hello "there"}
```

What we see in the above snippet is that a map `{:hello "there"}` is constructed by babashka CLI and then fed to the `prn` function. After that the result is printed to the console.

What if we want to influence how things are parsed by babashka CLI and provide some defaults? This can be done using metadata. Let’s create a `bb.edn` and make a file available on the classpath:

`bb.edn`:

```
{:paths ["."]}
```

`tasks.clj`:

```
(ns tasks
  {:org.babashka/cli {:exec-args {:ns-data 1}}})

(defn my-function
  {:org.babashka/cli {:exec-args {:fn-data 1}
                      :coerce {:num [:int]}
                      :alias {:n :num}}}
  [m] m)
```

Now let’s invoke:

```
$ bb --prn -x tasks/my-function -n 1 2
{:ns-data 1, :fn-data 1, :num [1 2]}
```

As you can see, the namespace options are merged with the function options. Defaults can be provided with `:exec-args`, like you’re used to from the clojure CLI.

### [exec](#cli:exec)

What about task integration? Let’s adapt our `bb.edn`:

```
{:paths ["."]
 :tasks {doit {:task (let [x (exec 'tasks/my-function)]
                       (prn :x x))
               :exec-args {:task-data 1234}}
         }}
```

and invoke the task:

```
$ bb doit --cli-option :yeah -n 1 2 3
:x {:ns-data 1, :fn-data 1, :task-data 1234, :cli-option :yeah, :num [1 2 3]}
```

As you can see it works similar to `-x`, but you can provide another set of defaults on the task level with `:exec-args`. Executing a function through babashka CLI is done using the `babashka.task/exec` function, available by default in tasks.

To add `:exec-args` that should be evaluated you can pass an extra map to `exec` as follows:

```
{:paths ["."]
 :tasks {doit {:task (let [x (exec 'tasks/my-function {:exec-args {:foo (+ 1 2 3)}})]
                       (prn :x x))
               :exec-args {:task-data 1234}}
         }}
```

```
$ bb doit --cli-option :yeah -n 1 2 3
:x {:ns-data 1, :fn-data 1, :task-data 1234, :cli-option :yeah, :num [1 2 3] :foo 6}
```

## [Badges](#badges)

You will notice babashka badges in some project READMEs.

[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](#badges) The babashka compatible badge means that a [library can be used as a babashka dependency](#projects).

[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](#badges) The babashka built-in badge indicates that a [library has been built directly into babashka](#built-in-namespaces), and requires no extra dependencies to use it.

### [Adding a Badge to your Project](#_adding_a_badge_to_your_project)

Help people who use babashka know that your library works with babashka.

[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](#badges) Add the babashka compatible badge if your library works as a babashka dependency. Let the world know by adding this badge to your project’s README:

Markdown

```
[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://book.babashka.org#badges)
```

AsciiDoc

```
https://book.babashka.org#badges[image:https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg[bb compatible]]
```

HTML

```
<a href="https://book.babashka.org#badges" rel="nofollow"><img src="https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg" alt="bb compatible" style="max-width: 100%;"></a>
```

[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](#badges) Add the babashka built-in badge if your library has the rare honor of being built into babashka. Add this badge to your project’s README:

Markdown

```
[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](https://book.babashka.org#badges)
```

AsciiDoc

```
https://book.babashka.org#badges[image:https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg[bb built-in]]
```

HTML

```
<a href="https://book.babashka.org#badges" rel="nofollow"><img src="https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg" alt="bb built-in" style="max-width: 100%;"></a>
```

## [Libraries](#libraries)

### [Built-in namespaces](#built-in-namespaces)

In addition to `clojure.core`, the following libraries / namespaces are available in babashka. Some are available through pre-defined aliases in the `user` namespace, which can be handy for one-liners. If not all vars are available, they are enumerated explicitly. If some important var is missing, an issue or PR is welcome.

From Clojure:

-   `clojure.core`
    
-   `clojure.core.protocols`: `Datafiable`, `Navigable`
    
-   `clojure.core.server`
    
-   `clojure.data`
    
-   `clojure.datafy`
    
-   `clojure.edn` aliased as `edn`
    
-   `clojure.instant`
    
-   `clojure.math`
    
-   `clojure.java.browse`
    
-   `clojure.java.javadoc`
    
-   `clojure.java.process`
    
-   `clojure.java.io` aliased as `io`:
    
    -   `as-relative-path`, `as-url`, `copy`, `delete-file`, `file`, `input-stream`, `make-parents`, `output-stream`, `reader`, `resource`, `writer`
        
    
-   `clojure.java.shell` aliased as `shell`
    
-   `clojure.main`: `demunge`, `ex-triage`, `repl`, `repl-read`, `repl-requires`
    
-   `clojure.pprint`: `pprint`, `cl-format`
    
-   `clojure.reflect`
    
-   `clojure.repl`
    
-   `clojure.set` aliased as `set`
    
-   `clojure.string` aliased as `str`
    
-   `clojure.stacktrace`
    
-   `clojure.test`
    
-   `clojure.test.junit`
    
-   `clojure.walk`
    
-   `clojure.zip`
    

Additional libraries:

-   [`babashka.cli`](https://github.com/babashka/cli): CLI arg parsing
    
-   [`babashka.http-client`](https://github.com/babashka/http-client): making HTTP requests
    
-   [`babashka.process`](https://github.com/babashka/process): shelling out to external processes
    
-   [`babashka.fs`](https://github.com/babashka/fs): file system manipulation
    
-   [`bencode.core`](https://github.com/nrepl/bencode) aliased as `bencode`: `read-bencode`, `write-bencode`
    
-   [`cheshire.core`](https://github.com/dakrone/cheshire) aliased as `json`: dealing with JSON
    
-   [`clojure.core.async`](https://clojure.github.io/core.async/) aliased as `async`.
    
-   [`clojure.data.csv`](https://github.com/clojure/data.csv) aliased as `csv`
    
-   [`clojure.data.xml`](https://github.com/clojure/data.xml) aliased as `xml`
    
-   [`clojure.data.json`](https://github.com/clojure/data.json)
    
-   [`clojure.data.priority-map`](https://github.com/clojure/data.priority-map)
    
-   [`clojure.tools.cli`](https://github.com/clojure/tools.cli) aliased as `tools.cli`
    
-   [`clojure.tools.reader`](https://github.com/clojure/tools.reader)
    
-   [`clojure.tools.deps`](https://github.com/clojure/tools.deps)
    
-   [`clojure.tools.build.api`](https://github.com/clojure/tools.build)
    
-   [`nrepl`](https://github.com/nrepl/nrepl): `nrepl.core`, `nrepl.server`, `nrepl.transport`, `nrepl.middleware`, `nrepl.misc`
    
-   [`clj-yaml.core`](https://github.com/clj-commons/clj-yaml) alias as `yaml`
    
-   [`cognitect.transit`](https://github.com/cognitect/transit-clj) aliased as `transit`
    
-   [`org.httpkit.client`](https://github.com/http-kit/http-kit)
    
-   [`org.httpkit.server`](https://github.com/http-kit/http-kit)
    
-   [`clojure.core.match`](https://github.com/clojure/core.match)
    
-   [`hiccup.core`](https://github.com/weavejester/hiccup/) and `hiccup2.core`
    
-   [`clojure.test.check`](https://github.com/clojure/test.check):
    
    -   `clojure.test.check`
        
    -   `clojure.test.check.generators`
        
    -   `clojure.test.check.properties`
        
    
-   [`rewrite-clj`](https://github.com/clj-commons/rewrite-clj):
    
    -   `rewrite-clj.parser`
        
    -   `rewrite-clj.node`
        
    -   `rewrite-clj.zip`
        
    -   `rewrite-clj.paredit`
        
    
-   [`Selmer`](https://github.com/yogthos/Selmer):
    
    -   `selmer.parser`
        
    
-   [`clojure.tools.logging`](https://github.com/clojure/tools.logging)
    
-   [`taoensso.timbre`](https://github.com/ptaoussanis/timbre): logging
    
-   [`edamame.core`](https://github.com/borkdude/edamame): Clojure parser
    
-   [`nextjournal.markdown`](https://github.com/nextjournal/markdown)
    
-   [`Jsoup`](https://github.com/jhy/jsoup)
    
-   [`JLine`](https://github.com/jline/jline3): terminal input handling
    

| Field | Value |
| --- | --- |
|  | Is your library built into babashka?<br>[Add the babashka built-in badge](#badges) to let the babashka community know!<br>[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](#badges) |

Check out the [babashka toolbox](https://babashka.org/toolbox/) and [projects](https://github.com/borkdude/babashka/blob/master/doc/projects.md) page for libraries that are not built-in, but which you can load as an external dependency in [`bb.edn`](https://book.babashka.org/#_bb_edn).

See the [build](https://github.com/borkdude/babashka/blob/master/doc/build.md) page for built-in libraries that can be enabled via feature flags, if you want to compile babashka yourself.

A selection of Java classes are available, see [`babashka/impl/classes.clj`](https://github.com/babashka/babashka/blob/master/src/babashka/impl/classes.clj) in babashka’s git repo.

If you want to use another, typically newer, version of a built-in library, after declaring (e.g. `org.babashka/cli {:mvn/version "<latest-version>"}`) in `bb.edn` or other means, use `require`'s `:reload`:

```
(require '[babashka.cli :as cli] :reload)
```

### [Babashka namespaces](#_babashka_namespaces)

#### [babashka.classpath](#babashka_classpath)

Available functions:

-   `add-classpath`
    
-   `get-classpath`
    
-   `split-classpath`
    

##### [add-classpath](#_add_classpath)

The function `add-classpath` which can be used to add to the classpath dynamically:

```
(require '[babashka.classpath :refer [add-classpath]]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def medley-dep '{:deps {medley {:git/url "https://github.com/borkdude/medley"
                                 :sha "91adfb5da33f8d23f75f0894da1defe567a625c0"}}})
(def cp (-> (sh "clojure" "-Spath" "-Sdeps" (str medley-dep)) :out str/trim))
(add-classpath cp)
(require '[medley.core :as m])
(m/index-by :id [{:id 1} {:id 2}]) ;;=> {1 {:id 1}, 2 {:id 2}}
```

##### [get-classpath](#_get_classpath)

The function `get-classpath` returns the classpath as set by `--classpath`, `BABASHKA_CLASSPATH` and `add-classpath`.

##### [split-classpath](#_split_classpath)

Given a classpath, returns a seq of strings as the result of splitting the classpath by the platform specific path separatator.

#### [babashka.deps](#babashkadeps)

Available functions:

-   `add-deps`
    
-   `clojure`
    
-   `merge-deps`
    

##### [add-deps](#_add_deps)

Use `add-deps` to resolve dependencies and add them to the babashka classpath. Pass a dependency map as the first argument:

```
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {medley/medley {:mvn/version "1.3.0"}}})

(require '[medley.core :as m])
(m/index-by :id [{:id 1} {:id 2}])
```

`add-deps` returns a sorted vector of the added libraries, or `nil` if it adds none. It keeps the versions of libraries already on the classpath.

Pass an optional map as the second argument to configure dependency resolution:

-   `:aliases`: a vector of alias keywords to use for dependency resolution.
    
-   `:force`: if `true`, resolve dependencies again instead of using the cached classpath.
    
-   `:env`: a map that replaces the environment for dependency resolution.
    
-   `:extra-env`: a map of environment variables to add.
    

Example:

```
(deps/add-deps '{:aliases {:medley {:extra-deps {medley/medley {:mvn/version "1.3.0"}}}}}
               {:aliases [:medley]})
```

##### [clojure](#_clojure)

The function `clojure` takes a sequential collection of arguments, similar to the clojure CLI. The arguments are then passed to [deps.clj](https://github.com/borkdude/deps.clj). The `clojure` function returns `nil` and prints to `*out*` for commands like `-Stree`, and `-Spath`. For `-M`, `-X` and `-A` it invokes `java` with `babashka.process/process` (see [babashka.process](#babashkaprocess)) and returns the associated record. For more details, read the docstring with:

```
(require '[clojure.repl :refer [doc]])
(doc babashka.deps/clojure)
```

Example:

The following script passes through command line arguments to clojure, while adding the medley dependency:

```
(require '[babashka.deps :as deps])

(def deps '{:deps {medley/medley {:mvn/version "1.3.0"}}})
(def clojure-args (list* "-Sdeps" deps  *command-line-args*))

(if-let [proc (deps/clojure clojure-args)]
  (-> @proc :exit (System/exit))
  (System/exit 0))
```

#### [babashka.wait](#babashkawait)

Contains the functions: `wait-for-port` and `wait-for-path`.

Usage of `wait-for-port`:

```
(wait/wait-for-port "localhost" 8080)
(wait/wait-for-port "localhost" 8080 {:timeout 1000 :pause 1000})
```

Waits for TCP connection to be available on host and port. Options map supports `:timeout` and `:pause`. If `:timeout` is provided and reached, `:default`'s value (if any) is returned. The `:pause` option determines the time waited between retries.

Usage of `wait-for-path`:

```
(wait/wait-for-path "/tmp/wait-path-test")
(wait/wait-for-path "/tmp/wait-path-test" {:timeout 1000 :pause 1000})
```

Waits for file path to be available. Options map supports `:default`, `:timeout` and `:pause`. If `:timeout` is provided and reached, `:default`'s value (if any) is returned. The `:pause` option determines the time waited between retries.

The namespace `babashka.wait` is aliased as `wait` in the `user` namespace.

#### [babashka.signal](#babashkasignal)

Contains the function `signal/pipe-signal-received?`. Usage:

```
(signal/pipe-signal-received?)
```

Returns true if `PIPE` signal was received. Example:

```
$ bb -e '((fn [x] (println x) (when (not (signal/pipe-signal-received?)) (recur (inc x)))) 0)' | head -n2
1
2
```

The namespace `babashka.signal` is aliased as `signal` in the `user` namespace.

#### [babashka.http-client](#babashkahttpclient)

The `babashka.http-client` library for making HTTP requests. See [babashka.http-client](https://github.com/babashka/http-client) for how to use it.

#### [babashka.process](#babashkaprocess)

The `babashka.process` library. See the [process](https://github.com/babashka/process) repo for API docs.

#### [babashka.fs](#babashkafs)

The `babashka.fs` library offers file system utilities. See the [fs](https://github.com/babashka/fs) repo for API docs.

#### [babashka.cli](#babashkacli)

The `babashka.cli` library allows you to turn functions into CLIs. See the [cli](https://github.com/babashka/cli) repo for API docs and check out the [babashka CLI](https://book.babashka.org/#_babashka_cli) chapter on how to use it from the command line or with [tasks](https://book.babashka.org/#tasks).

#### [babashka.terminal](#babashkaterminal)

Use `tty?` to check if `:stdin`, `:stdout` or `:stderr` is a terminal:

```
(require '[babashka.terminal :refer [tty?]])

(tty? :stdout)
```

#### [babashka.ffi](#babashkaffi)

| Field | Value |
| --- | --- |
|  | `babashka.ffi` is experimental. |

Use `babashka.ffi` to call C functions in shared libraries. See the [ffi guide](https://github.com/babashka/ffi/blob/main/doc/guide.md) for examples.

### [Projects](#projects)

Babashka is able to run Clojure projects from source, if they are compatible with the subset of Clojure that sci is capable of running.

Check this [page](https://github.com/borkdude/babashka/blob/master/doc/projects.md) for projects that are known to work with babashka.

| Field | Value |
| --- | --- |
|  | Is your library compatible with babashka?<br>[Add the babashka compatible badge](#badges), the babashka community will appreciate it!<br>[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](#badges) |

## [Pods](#pods)

Pods are programs that can be used as a Clojure library by babashka. Documentation is available in the [library repo](https://github.com/babashka/babashka.pods).

A list of available pods can be found [here](https://github.com/borkdude/babashka/blob/master/doc/projects.md#pods).

### [Pod registry](#_pod_registry)

Since bb 0.2.6 pods can be obtained via the [pod-registry](https://github.com/babashka/pod-registry).

This is an example script which uses the [fswatcher](https://github.com/babashka/pod-babashka-fswatcher) pod to watch a directory for changes:

```
#!/usr/bin/env bb

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/fswatcher "0.0.5")

(require '[pod.babashka.fswatcher :as fw])

(fw/watch "." prn {:delay-ms 5000})

(println "Watching current directory for changes... Press Ctrl-C to quit.")

@(promise)
```

### [Pods in bb.edn](#_pods_in_bb_edn)

Since bb 0.8.0 pods can be declared in `bb.edn`:

```
{:paths ["bb"]
 :pods {org.babashka/go-sqlite3 {:version "0.2.3"}}}
```

Given the file `bb/my_project/db.clj`:

```
(ns my-project.db
  (:require [pod.babashka.go-sqlite3 :as sqlite]))

(defn -main [& _args]
  (prn (sqlite/query ":memory:" ["SELECT 1 + 1 AS sum"])))
```

you can then execute the main function, without calling `load-pod` manually:

```
$ bb -m my-project.db
[{:sum 2}]
```

## [Style](#style)

A note on style. Babashka recommends the following:

### [Explicit requires](#_explicit_requires)

Use explicit requires with namespace aliases in scripts, unless you’re writing one-liners.

Do this:

```
$ ls | bb -i '(-> *input* first (str/includes? "m"))'
true
```

But not this:

script.clj:

```
(-> *input* first (str/includes? "m"))
```

Rather do this:

script.clj:

```
(ns script
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))
(-> (io/reader *in*) line-seq first (str/includes? "m"))
```

Some reasons for this:

-   Linters like clj-kondo work better with code that uses namespace forms, explicit requires, and known Clojure constructs
    
-   Editor tooling works better with namespace forms (sorting requires, etc).
    
-   Writing compatible code gives you the option to run the same script with `clojure`
    

## [Child processes](#child_processes)

For child processes, the babashka [process](https://github.com/babashka/process) library is recommended. It is built into babashka. Check out the README which gives a good introduction into the library.

## [Recipes](#recipes)

### [Running tests](#_running_tests)

Babashka bundles `clojure.test`. To run tests you can write a test runner script. Given the following project structure:

```
.
├── src
│   └──...
└── test
    └── your
        ├── test_a.clj
        └── test_b.clj
```

test-runner.clj

```
#!/usr/bin/env bb

(require '[clojure.test :as t]
         '[babashka.classpath :as cp])

(cp/add-classpath "src:test")                        (1)

(require 'your.test-a 'your.test-b)                  (2)

(def test-results
  (t/run-tests 'your.test-a 'your.test-b))           (3)

(let [{:keys [fail error]} test-results]
  (when (pos? (+ fail error))
    (System/exit 1)))                                (4)
```

| Field | Value |
| --- | --- |
| **1** | Add sources and tests to the classpath |
| **2** | Require the test namespaces |
| **3** | Run all tests in the test namespaces |
| **4** | Exit the test script with a non-zero exit code when there are failures or errors |

### [Main file](#main_file)

In Python scripts there is a well-known pattern to check if the current file was the file invoked from the command line, or loaded from another file: the `__name__ == "__main__"` pattern. In babashka this pattern can be implemented with:

```
(= *file* (System/getProperty "babashka.file"))
```

Combining this with a conditional invocation of `-main` creates a script file that is safe to load at a REPL, and easy to invoke at the CLI.

```
#!/usr/bin/env bb

;; Various functions defined here

(defn -main [& args]
;; Implementation of main
)

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
```

This can be exceedingly handy for editing complex scripts interactively, while not being able to adjust how they are invoked by other tools.

### [Shutdown hook](#_shutdown_hook)

Adding a shutdown hook allows you to execute some code before the script exits.

```
$ bb -e '(-> (Runtime/getRuntime) (.addShutdownHook (Thread. #(println "bye"))))'
bye
```

This also works when the script is interrupted with ctrl-c.

### [Printing returned values](#_printing_returned_values)

Babashka doesn’t print a returned `nil` as lots of scripts end in something side-effecting.

```
$ bb -e '(:a {:a 5})'
5
$ bb -e '(:b {:a 5})'
$
```

If you really want to print the nil, you can use `(prn ..)` instead.

#### [HTTP over Unix sockets](#_http_over_unix_sockets)

This can be useful for talking to Docker:

```
(require '[clojure.java.shell :refer [sh]])
(require '[cheshire.core :as json])
(-> (sh "curl" "--silent"
        "--no-buffer" "--unix-socket"
        "/var/run/docker.sock"
        "http://localhost/images/json")
    :out
    (json/parse-string true)
    first
    :RepoTags) ;;=> ["borkdude/babashka:latest"]
```

### [Core.async](#core_async)

In addition to `future`, `pmap`, `promise` and friends, you may use the `clojure.core.async` namespace for asynchronous scripting. The following example shows how to get first available value from two different processes:

```
bb -e '
(defn async-command [& args]
  (async/thread (apply shell/sh "bash" "-c" args)))

(-> (async/alts!! [(async-command "sleep 2 && echo process 1")
                   (async-command "sleep 1 && echo process 2")])
    first :out str/trim println)'
process 2
```

Caveat: currently the `go` macro is available for compatibility with JVM programs, but the implementation maps to `clojure.core.async/thread` and the single exclamation mark operations (`<!`, `>!`, etc.) map to the double exclamation mark operations (`<!!`, `>!!`, etc.). It will not "park" threads, like on the JVM.

Examples like the following may still work, but will take a lot more system resources than on the JVM and will break down for some high value of `n`:

```
(require '[clojure.core.async :as async])

(def n 1000)

(let [cs (repeatedly n async/chan)
      begin (System/currentTimeMillis)]
  (doseq [c cs] (async/go (async/>! c "hi")))
  (dotimes [_ n]
    (let [[v _] (async/alts!! cs)]
      (assert (= "hi" v))))
  (println "Read" n "msgs in" (- (System/currentTimeMillis) begin) "ms"))
```

### [Interacting with an nREPL server](#_interacting_with_an_nrepl_server)

Babashka comes with the [nrepl/bencode](https://github.com/nrepl/bencode) library which allows you to read and write bencode messages to a socket. A simple example which evaluates a Clojure expression on an nREPL server started with `lein repl`:

```
(ns nrepl-client
  (:require [bencode.core :as b]))

(defn nrepl-eval [port expr]
  (let [s (java.net.Socket. "localhost" port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "eval" "code" expr})
        bytes (get (b/read-bencode in) "value")]
    (String. bytes)))

(nrepl-eval 52054 "(+ 1 2 3)") ;;=> "6"
```

Note that the above is a bit low level. You can also use the [nrepl-client](https://github.com/babashka/nrepl-client) library for this.

### [Running from Cygwin/Git Bash](#_running_from_cygwingit_bash)

On Windows, `bb` can be invoked from the bash shell directly:

```
$ bb -e '(+ 1 2 3)'
6
```

However, creating a script that invokes `bb` via a shebang leads to an error if the script is not in the current directory. Suppose you had the following script named `hello` on your path:

```
#!/usr/bin/env bb
(println "Hello, world!")
```

```
$ hello
----- Error --------------------------------------------------------------------
Type:     java.lang.Exception
Message:  File does not exist: /cygdrive/c/path/to/hello
```

The problem here is that the shell is passing a Cygwin-style path to `bb`, but `bb` can’t recognize it because it wasn’t compiled with Cygwin.

The solution is to create a wrapper script that converts the Cygwin-style path to a Windows-style path before invoking `bb`. Put the following into a script called `bbwrap` somewhere on your Cygwin path, say in `/usr/local/bin/bbwrap`:

```
#!/bin/bash
SCRIPT=$1
shift
bb.exe $(cygpath -w $SCRIPT) $@
```

Make sure to fix your original script to invoke `bbwrap` instead of `bb` directly:

```
#!/usr/bin/env bbwrap
(println "Hello, world!")
```

### [Throwing exception to exit program](#_throwing_exception_to_exit_program)

Babashka supports adding `:babashka/exit` metadata to an `ex-info` to indicate the exit code of babashka when this exception isn’t caught by any other parts of the program. The `:babashka/exit` code also indicates that no error report should be printed.

```
---
$ bb -e '(throw (ex-info "Error happened" {:babashka/exit 1}))'
Error happened
$ echo $?
1
---
```

## [Differences with Clojure](#differences-with-clojure)

Babashka is implemented using the [Small Clojure Interpreter](https://github.com/borkdude/sci). This means that a snippet or script is not compiled to JVM bytecode, but executed form by form by a runtime which implements a substantial subset of Clojure. Babashka is compiled to a native binary using [GraalVM](https://github.com/oracle/graal). It comes with a selection of built-in namespaces and functions from Clojure and other useful libraries. The data types (numbers, strings, persistent collections) are the same. Multi-threading is supported (`pmap`, `future`).

Differences with Clojure:

-   A pre-selected set of Java classes are supported. You cannot add Java classes at runtime.
    
-   Interpretation comes with overhead. Therefore loops are slower than in Clojure on the JVM. In general interpretation yields slower programs than compiled programs.
    
-   `deftype` support is limited to protocols, `Object` and `IPersistentMap`.
    
-   `definterface` is not supported
    
-   `defprotocol` and `defrecord` are implemented using multimethods and regular maps. Ostensibly they work the same, but under the hood there are no Java classes that correspond to them. Do not depend on this implementation detail since it may change in future version of babashka.
    
-   Currently `reify` works only for one class at a time
    

## [Resources](#resources)

Check out the list of resources in babashka’s [README.md](https://github.com/babashka/babashka#articles-podcasts-and-videos).

### [Books](#_books)

#### [#\_babashka\_babooka](#_babashka_babooka)[Babashka Babooka](https://www.braveclojure.com/quests/babooka/)

If you’re a fan of [Clojure for the Brave and True](https://www.braveclojure.com/clojure-for-the-brave-and-true/), you might enjoy [Babashka Babooka](https://www.braveclojure.com/quests/babooka/), a book by the same author, Daniel Higginbotham!

## [Contributing](#contributing)

Visit Babashka book’s [Github repository](https://github.com/babashka/book) and make an issue and/or PR.

## [License](#license)

Copyright © 2020-2021 Michiel Borkent

Licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0).