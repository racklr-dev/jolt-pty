# jolt-pty

POSIX pseudo-terminal primitives for [jolt](https://github.com/jolt-lang/jolt), bound
to libc through `jolt.ffi`. This is the reusable PTY core of dirge's
`sandbox/microvm/pty_harness.rs` (`open_pty`, raw mode, termios save/restore,
stdin redirect) as a standalone library.

```clojure
(require '[jolt.pty :as pty])

(let [p (pty/open-pty!)]           ; => {:primary-fd 4 :secondary-fd 6 :secondary-name "/dev/pts/5"}
  (pty/make-raw! (:secondary-fd p))    ; no echo, no canonical buffering (returns prev termios)
  (pty/write-fd (:primary-fd p) (.getBytes "hello" "UTF-8"))  ; -> secondary input
  (String. (pty/read-fd (:secondary-fd p) 5) "UTF-8")            ; "hello"
  (pty/close-pty! p))
```

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

## Notes

- termios snapshots are byte-arrays over a fixed 256-byte buffer — generous
  for both glibc x86_64/aarch64 (60 bytes) and macOS (72 bytes); `tcgetattr`
  only writes `sizeof(struct termios)`.
- Constants (`O_RDWR`, `O_NOCTTY`, `TCSANOW`) are resolved per platform via
  `os.name`.
- No external deps: `jolt.ffi` is in jolt's stdlib, and libc symbols resolve
  from the process.

## License

EPL-2.0
