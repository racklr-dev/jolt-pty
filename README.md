# jolt-pty

POSIX pseudo-terminal primitives for [jolt](https://github.com/jolt-lang/jolt), bound
to libc through `jolt.ffi`. This is the reusable PTY core of dirge's
`sandbox/microvm/pty_harness.rs` (`open_pty`, raw mode, termios save/restore,
stdin redirect) plus the console-relay half of `ui/pty_relay.rs`.

```clojure
(require '[jolt.pty :as pty])

(let [p (pty/open-pty!)]           ; => {:primary-fd 4 :secondary-fd 6 :secondary-name "/dev/pts/5"}
  (pty/make-raw! (:secondary-fd p))    ; no echo, no canonical buffering (returns prev termios)
  (pty/write-fd (:primary-fd p) (.getBytes "hello" "UTF-8"))  ; -> secondary input
  (String. (pty/read-fd (:secondary-fd p) 5) "UTF-8")            ; "hello"
  (pty/close-pty! p))
```

## Interactive relay

```clojure
(let [p (pty/open-pty!)
      child (pty/spawn-secondary! p ["ssh" "-i" key "-p" port "user@host"])]
  (pty/relay! p child))   ; copies stdio <-> pty, applies host terminal size, returns exit code
```

`relay!` copies bytes between the pty primary and host stdin/stdout until the
child exits, first copying the host terminal size to the pty. This is the core
of dirge's `attach` (SSH into the sandbox over an isolated pty). The RFC 4254
§8 ECHO=1 handshake from dirge's relay is intentionally not ported: it exists
only because dirge's TUI owns `/dev/tty` in raw mode, which a plain library
consumer does not do.

## API

- `open-pty!` — `posix_openpt` + `grantpt`/`unlockpt`/`ptsname`/`open`;
  returns `{:primary-fd :secondary-fd :secondary-name}`.
- `close-pty!` / `close!` — close one or both fds.
- `read-fd` / `write-fd` — raw byte reads/writes on an fd.
- `make-raw!` — put an fd's termios into raw mode, returning the previous
  snapshot; `restore-termios!` puts it back.
- `capture-termios` / `restore-termios!` — snapshot and restore terminal settings.
- `redirect!` / `redirect-stdin!` — `dup2` one fd onto another (or stdin).
- `dup-fd` — `dup` a descriptor.
- `get-winsize` / `set-winsize!` — `TIOCGWINSZ` / `TIOCSWINSZ` window size.
- `spawn-secondary!` — spawn argv with stdio on the pty secondary (setsid,
  controlling terminal). Returns a Process.
- `relay!` — copy stdio <-> pty until the child exits; returns its exit status.

## Notes

- termios snapshots are byte-arrays over a fixed 256-byte buffer — generous
  for both glibc x86_64/aarch64 (60 bytes) and macOS (72 bytes); `tcgetattr`
  only writes `sizeof(struct termios)`.
- Constants (`O_RDWR`, `O_NOCTTY`, `TCSANOW`) are resolved per platform via
  `os.name`; `TIOCGWINSZ`/`TIOCSWINSZ` are Linux values (0x5413/0x5414).
- No external deps: `jolt.ffi` is in jolt's stdlib, and libc symbols resolve
  from the process.

## License

EPL-2.0
