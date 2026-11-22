;; Relay + winsize test: apply a size, spawn a child on the pty secondary,
;; relay bytes to host stdout, and check the child's exit status flows back.
(ns jolt.test.pty-relay
  (:require [jolt.pty :as pty]))

(let [p (pty/open-pty!)]
  (assert (:primary-fd p) "open-pty! must return a primary fd")

  ;; winsize round-trip on the primary
  (assert (zero? (pty/set-winsize! (:primary-fd p) 24 80)))
  (let [ws (pty/get-winsize (:primary-fd p))]
    (assert (= 24 (:rows ws)) "rows round-trip")
    (assert (= 80 (:cols ws)) "cols round-trip"))

  ;; spawn a child whose stdout is the secondary, relay to host stdout, exit 7
  (let [child (pty/spawn-secondary! p ["sh" "-c" "echo relay-hello; exit 7"])
        code (pty/relay! p child)]
    (assert (= 7 code) (str "expected exit 7, got " code)))

  (pty/close-pty! p))

(println "jolt-pty: winsize round-trip + spawn-secondary + relay + exit-status OK")
