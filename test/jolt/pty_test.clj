;; jolt-pty smoke test: open a pty, raw mode, bidirectional echo-free round-trip.
(ns jolt.test.pty
  (:require [jolt.pty :as pty]
            [clojure.string :as str]))

(defn ->bytes [s] (.getBytes s "UTF-8"))

(let [p (pty/open-pty!)]
  (println :secondary-name (:secondary-name p))
  (assert (str/starts-with? (:secondary-name p) "/dev/"))

  ;; raw mode on the secondary end kills echo + canonical buffering, so a write
  ;; to the primary end is readable on the secondary immediately and is NOT
  ;; echoed back.
  (let [prev (pty/make-raw! (:secondary-fd p))]
    ;; primary -> secondary
    (pty/write-fd (:primary-fd p) (->bytes "hello"))
    (assert (= "hello" (String. (pty/read-fd (:secondary-fd p) 5) "UTF-8")))

    ;; secondary -> primary
    (pty/write-fd (:secondary-fd p) (->bytes "world"))
    (assert (= "world" (String. (pty/read-fd (:primary-fd p) 5) "UTF-8")))

    ;; termios capture/restore round-trip
    (let [snap (pty/capture-termios (:secondary-fd p))]
      (assert (zero? (pty/restore-termios! (:secondary-fd p) snap))))

    ;; restore the pre-raw settings
    (assert (zero? (pty/restore-termios! (:secondary-fd p) prev))))

  (pty/close-pty! p))

(println "jolt-pty: open + raw + bidirectional round-trip + termios restore OK")
