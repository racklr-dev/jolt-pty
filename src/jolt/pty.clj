;; POSIX pseudo-terminal primitives over libc, via jolt.ffi.
;; Ports the PTY core of dirge's sandbox/microvm/pty_harness.rs (open_pty,
;; raw mode, termios save/restore, stdin redirect) as a reusable library.
;;
;; A pty is two fds: writes to the PRIMARY end appear as input readable on the
;; SECONDARY end, and writes to the SECONDARY end appear as output readable on
;; the PRIMARY end (with the secondary end's termios line discipline — echo,
;; canonical buffering — applied).
(ns jolt.pty
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; -- libc bindings -----------------------------------------------------------
(ffi/defcfn c-posix-openpt "posix_openpt" [:int] :int)
(ffi/defcfn c-grantpt "grantpt" [:int] :int)
(ffi/defcfn c-unlockpt "unlockpt" [:int] :int)
(ffi/defcfn c-ptsname "ptsname" [:int] :pointer)
(ffi/defcfn c-open "open" [:string :int :&] :int)
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-dup "dup" [:int] :int)
(ffi/defcfn c-dup2 "dup2" [:int :int] :int)
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t)
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t)
(ffi/defcfn c-tcgetattr "tcgetattr" [:int :pointer] :int)
(ffi/defcfn c-tcsetattr "tcsetattr" [:int :int :pointer] :int)
(ffi/defcfn c-cfmakeraw "cfmakeraw" [:pointer] :void)

;; -- platform constants ------------------------------------------------------
(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(def ^:const O-RDWR 2)
(def ^:const O-NOCTTY (if macos? 0x20000 0x100))
(def ^:const TCSANOW 0)

;; struct termios is 60 bytes on glibc x86_64/aarch64 and 72 on macOS; a fixed
;; generous buffer is enough for capture/restore, since tcgetattr only writes
;; sizeof(struct termios) and tcsetattr only reads that much back.
(def ^:const termios-bytes 256)

;; -- fd helpers --------------------------------------------------------------
(defn close!
  "Close a file descriptor; safe to call more than once. Returns the close rc."
  [fd]
  (if (and fd (not (neg? fd)))
    (c-close fd)
    0))

(defn dup-fd
  "Duplicate a file descriptor; returns the new fd (or throws)."
  [fd]
  (let [n (c-dup fd)]
    (when (neg? n)
      (throw (ex-info "dup failed" {:fd fd :errno (ffi/errno)})))
    n))

(defn redirect!
  "dup2 src-fd onto dst-fd. Returns dst-fd."
  [src-fd dst-fd]
  (let [rc (c-dup2 src-fd dst-fd)]
    (when (neg? rc)
      (throw (ex-info "dup2 failed" {:src src-fd :dst dst-fd :errno (ffi/errno)})))
    dst-fd))

(defn redirect-stdin!
  "Make fd the process's standard input (dup2 onto fd 0)."
  [fd]
  (redirect! fd 0))

(defn read-fd
  "Read up to n bytes from fd; returns a byte-array (possibly shorter)."
  [fd n]
  (let [p (ffi/alloc n)]
    (try
      (let [m (c-read fd p n)]
        (when (neg? m)
          (throw (ex-info "read failed" {:fd fd :errno (ffi/errno)})))
        (ffi/read-array p m))
      (finally (ffi/free p)))))

(defn write-fd
  "Write every octet of byte-array b to fd; returns the count written."
  [fd b]
  (let [n (alength b)
        p (ffi/alloc n)]
    (try
      (ffi/write-array p b)
      (let [m (c-write fd p n)]
        (when (neg? m)
          (throw (ex-info "write failed" {:fd fd :errno (ffi/errno)})))
        m)
      (finally (ffi/free p)))))

;; -- termios -----------------------------------------------------------------
(defn capture-termios
  "Read fd's current terminal settings into a byte-array snapshot."
  [fd]
  (let [p (ffi/alloc termios-bytes)]
    (try
      (let [rc (c-tcgetattr fd p)]
        (when-not (zero? rc)
          (throw (ex-info "tcgetattr failed" {:fd fd :errno (ffi/errno)})))
        (ffi/read-array p termios-bytes))
      (finally (ffi/free p)))))

(defn restore-termios!
  "Restore fd's terminal settings from a snapshot captured by capture-termios.
  Returns the tcsetattr rc."
  [fd snapshot]
  (let [p (ffi/alloc termios-bytes)]
    (try
      (ffi/write-array p snapshot)
      (let [rc (c-tcsetattr fd TCSANOW p)]
        (when-not (zero? rc)
          (throw (ex-info "tcsetattr failed" {:fd fd :errno (ffi/errno)})))
        rc)
      (finally (ffi/free p)))))

(defn make-raw!
  "Put fd's terminal into raw mode (no echo, no canonical buffering).
  Returns the PREVIOUS termios snapshot; pass it to restore-termios! to undo."
  [fd]
  (let [prev (capture-termios fd)
        p (ffi/alloc termios-bytes)]
    (try
      (ffi/write-array p prev)
      (c-cfmakeraw p)
      (let [rc (c-tcsetattr fd TCSANOW p)]
        (when-not (zero? rc)
          (throw (ex-info "tcsetattr(raw) failed" {:fd fd :errno (ffi/errno)})))
        prev)
      (finally (ffi/free p)))))

;; -- pty ---------------------------------------------------------------------
(defn open-pty!
  "Open a pseudo-terminal pair. Returns {:primary-fd :secondary-fd :secondary-name}.
  The secondary name is the /dev/pts/N path; close both fds with close-pty!."
  []
  (let [primary (c-posix-openpt (bit-or O-RDWR O-NOCTTY))]
    (when (neg? primary)
      (throw (ex-info "posix_openpt failed" {:errno (ffi/errno)})))
    (try
      (when-not (zero? (c-grantpt primary))
        (throw (ex-info "grantpt failed" {:errno (ffi/errno)})))
      (when-not (zero? (c-unlockpt primary))
        (throw (ex-info "unlockpt failed" {:errno (ffi/errno)})))
      (let [secondary-name (ffi/ptr->string (c-ptsname primary))]
        (when-not secondary-name
          (throw (ex-info "ptsname returned NULL" {})))
        (let [secondary (c-open secondary-name (bit-or O-RDWR O-NOCTTY))]
          (when (neg? secondary)
            (throw (ex-info "open(secondary) failed" {:secondary secondary-name :errno (ffi/errno)})))
          {:primary-fd primary :secondary-fd secondary :secondary-name secondary-name}))
      (catch Throwable e
        (c-close primary)
        (throw e)))))

(defn close-pty!
  "Close both fds of a pty opened by open-pty!. Returns nil."
  [pty]
  (close! (:primary-fd pty))
  (close! (:secondary-fd pty))
  nil)
