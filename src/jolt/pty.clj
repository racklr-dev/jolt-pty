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

;; -- window size + relay -----------------------------------------------------
;; The console-relay half of dirge's pty_relay.rs: apply the host terminal size
;; to the pty, then copy bytes between the pty primary and host stdio until the
;; child exits. (The RFC 4254 ECHO handshake in dirge's relay exists because
;; its TUI owns /dev/tty in raw mode; a plain library consumer whose stdio is a
;; normal terminal does not need it, so it is deliberately not ported here.)
(ffi/defcfn c-ioctl "ioctl" [:int :ulong :pointer] :int)

(def ^:const TIOCGWINSZ 0x5413)  ; Linux x86_64/aarch64
(def ^:const TIOCSWINSZ 0x5414)

(defn get-winsize
  "Read a tty's window size via TIOCGWINSZ. Returns {:rows :cols}, or nil if
  fd is not a tty."
  [fd]
  (let [p (ffi/alloc 8)]
    (try
      (if (zero? (c-ioctl fd TIOCGWINSZ p))
        {:rows (ffi/read p :ushort 0) :cols (ffi/read p :ushort 2)}
        nil)
      (finally (ffi/free p)))))

(defn set-winsize!
  "Set a tty's window size via TIOCSWINSZ. Returns the ioctl rc (0 on success)."
  [fd rows cols]
  (let [p (ffi/alloc 8)]
    (try
      (ffi/write p :ushort rows 0)
      (ffi/write p :ushort cols 2)
      (c-ioctl fd TIOCSWINSZ p)
      (finally (ffi/free p)))))

(defn- apply-host-winsize!
  "Copy the host terminal's size (from stdin, fd 0) onto the pty primary,
  falling back to 80x24 when stdin is not a tty."
  [primary-fd]
  (let [{:keys [rows cols]} (or (get-winsize 0) {:rows 24 :cols 80})]
    (set-winsize! primary-fd rows cols)))

(defn spawn-secondary!
  "Spawn argv with stdin/stdout/stderr attached to the pty secondary, as a
  session leader (setsid) so the secondary becomes its controlling terminal.
  Returns the Process. Pair with relay!."
  [pty argv]
  (let [name (:secondary-name pty)
        wrapper (str "exec \"$@\" 0<" name " 1>" name " 2>&1")
        args (concat ["setsid" "sh" "-c" wrapper "sh"] (map str argv))
        pb (ProcessBuilder. (into-array String args))]
    (.start pb)))

(defn- relay-read-loop! [fd out]
  (try
    (loop []
      (let [b (read-fd fd 4096)]
        (if (zero? (alength b))
          :eof
          (do (.write out b) (.flush out) (recur)))))
    (catch Throwable _ :eof)))

(defn relay!
  "Relay bytes between the pty primary and host stdio until child exits.
  Applies the host terminal size first. Returns the child's exit status.
  child must have been spawned by spawn-secondary!."
  [pty child]
  (let [master (:primary-fd pty)
        done (atom false)
        m2s (future (relay-read-loop! master System/out))
        s2m (future
              (loop []
                (when-not @done
                  (let [avail (.available System/in)]
                    (if (pos? avail)
                      (let [buf (byte-array (min avail 4096))
                            n (.read System/in buf)]
                        (when (pos? n)
                          (let [chunk (if (= n (alength buf))
                                        buf
                                        (let [c (byte-array n)]
                                          (System/arraycopy buf 0 c 0 n)
                                          c))]
                            (write-fd master chunk))))
                      (Thread/sleep 50))
                    (recur)))))
        code (.waitFor child)]
    (reset! done true)
    (close! master)                       ; unblock the primary reader
    (try (deref m2s 2000 nil) (catch Throwable _ nil))
    (try (deref s2m 2000 nil) (catch Throwable _ nil))
    code))
