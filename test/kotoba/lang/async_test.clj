(ns kotoba.lang.async-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(def source (slurp "src/kotoba/lang/async.kotoba"))
(def keyword-a ["keyword" :a])
(def keyword-b ["keyword" :b])
(def keyword-c ["keyword" :c])

(defn call [kir function & args] (ir/execute kir function args))
(defn vector-items [document]
  (let [[tag items] document] (is (= "vector" tag)) items))
(defn map-value [document key]
  (second (some #(when (= key (first %)) %) (second document))))

(deftest reference-preserves-bounded-channel-state-machine
  (let [kir (:kir (compiler/compile-source source :js-kotoba-v1))
        fixed (call kir 'fixed-chan 2)
        [c1 ok1] (vector-items (call kir 'put fixed keyword-a))
        [c2 ok2] (vector-items (call kir 'put c1 keyword-b))
        [c3 ok3] (vector-items (call kir 'put c2 keyword-c))
        [c4 first-value] (vector-items (call kir 'take c2))]
    (is (= ["bool" true] ok1))
    (is (= ["bool" true] ok2))
    (is (= ["bool" false] ok3))
    (is (= c2 c3))
    (is (= keyword-a first-value))
    (is (= [keyword-b] (second (map-value c4 :buffer))))
    (is (false? (call kir 'can-put? c2)))
    (is (true? (call kir 'can-take? c2)))
    (testing "dropping and sliding policies"
      (let [dropping (call kir 'chan :dropping 2)
            d1 (first (vector-items (call kir 'put dropping keyword-a)))
            d2 (first (vector-items (call kir 'put d1 keyword-b)))
            d3 (first (vector-items (call kir 'put d2 keyword-c)))
            sliding (call kir 'chan :sliding 2)
            s1 (first (vector-items (call kir 'put sliding keyword-a)))
            s2 (first (vector-items (call kir 'put s1 keyword-b)))
            s3 (first (vector-items (call kir 'put s2 keyword-c)))]
        (is (= [keyword-a keyword-b] (second (map-value d3 :buffer))))
        (is (= [keyword-b keyword-c] (second (map-value s3 :buffer))))))
    (testing "close and drain"
      (let [closed (call kir 'close c2)
            [unchanged accepted] (vector-items (call kir 'put closed keyword-c))
            [empty drained] (vector-items (call kir 'drain c2))]
        (is (= closed unchanged))
        (is (= ["bool" false] accepted))
        (is (true? (call kir 'closed? closed)))
        (is (= [keyword-a keyword-b] (second drained)))
        (is (= [] (second (map-value empty :buffer))))))
    (testing "invalid capacity and kind fail closed"
      (is (thrown? clojure.lang.ExceptionInfo (call kir 'chan :fixed 0)))
      (is (thrown? clojure.lang.ExceptionInfo (call kir 'chan :unknown 2))))))

(defn compiler-root []
  (nth (iterate #(.getParent ^java.nio.file.Path %)
                (java.nio.file.Path/of (.toURI (io/resource "kotoba/compiler/core.clj")))) 4))
(defn base64 [value] (.encodeToString (java.util.Base64/getEncoder) value))

(deftest restricted-javascript-and-typed-wasm-preserve-channel-behavior
  (let [javascript (compiler/compile-source source :js-kotoba-v1)
        wasm (compiler/compile-source source :wasm32-browser-kotoba-v1)
        js64 (base64 (.getBytes ^String (:source javascript) "UTF-8"))
        wasm64 (base64 ^bytes (:bytes wasm))
        probe (shell/sh
               "node" "--input-type=module" "-e"
               (str "import(process.argv[1]).then(async host=>{"
                    "const j=await import('data:text/javascript;base64," js64 "');"
                    "const w=await host.instantiateKotoba(Buffer.from(process.argv[2],'base64'));"
                    "const freeze=v=>{if(Array.isArray(v)){for(const x of v)freeze(x);Object.freeze(v)}return v};"
                    "const localValue=s=>freeze(['keyword',s]),get=(d,k)=>d[1].find(e=>e[0]===k)[1];"
                    "const run=(x,value)=>{let c=x.chan(':sliding',2n);"
                    "c=x.put(c,value(':a'))[1][0];c=x.put(c,value(':b'))[1][0];"
                    "const p=x.put(c,value(':c')),n=p[1][0],items=get(n,':buffer')[1];"
                    "if(p[1][1][1]!==true||items[0][1]!==':b'||items[1][1]!==':c')throw Error('sliding');"
                    "const t=x.take(n);if(t[1][1][1]!==':b'||get(t[1][0],':buffer')[1].length!==1)throw Error('take');"
                    "const closed=x.close(n),rejected=x.put(closed,value(':d'));"
                    "if(rejected[1][1][1]!==false||x['closed?'](closed)!==true)throw Error('close');};"
                    "run(j.instantiateKotoba({}),localValue);"
                    "run(w.instance.exports,s=>w.typedValues.document(['keyword',s]));"
                    "for(const x of [j.instantiateKotoba({}),w.instance.exports])for(const args of [[':fixed',0n],[':unknown',2n]]){let denied=false;try{x.chan(...args)}catch(e){denied=true}if(!denied)throw Error('bounds')}"
                    "}).catch(e=>{console.error(e);process.exit(99)})")
               (.toString (.toUri (.resolve (compiler-root) "runtime/browser-host.mjs"))) wasm64)]
    (is (zero? (:exit probe)) (:err probe))))

(deftest production-source-authority
  (is (= ["src/kotoba/lang/async.kotoba"]
         (->> (file-seq (io/file "src")) (filter #(.isFile %)) (map str) sort vec))))
