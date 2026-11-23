# jolt-pty — POSIX pseudo-terminal primitives

POSIX pseudo-terminal primitives for [jolt](https://github.com/jolt-lang/jolt),
bound to libc through [jolt.ffi](https://github.com/jolt-lang/jolt). This is
the reusable PTY core of dirge's sandbox/microvm pty harness (`open_pty`, raw
mode, termios save/restore, stdin redirect) plus the console-relay half of its
pty relay.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ to run the tests
- A POSIX host (Linux or macOS); no external libraries — libc symbols resolve
  from the process

## Build

Nothing to compile. Run the suite:

```sh
jolt run test/jolt/pty_test.clj
jolt run test/jolt/pty_relay_test.clj
```

## Usage

```clojure
(require '[jolt.pty :as pty])

(let [p (pty/open-pty!)]          ; => {:primary-fd 4 :secondary-fd 6 :secondary-name "/dev/pts/5"}
  (pty/make-raw! (:secondary-fd p))   ; no echo/canonical buffering; returns prev termios
  (pty/write-fd (:primary-fd p) (.getBytes "hello" "UTF-8"))
  (String. (pty/read-fd (:secondary-fd p) 5) "UTF-8")   ; => "hello"
  (pty/close-pty! p))
```

Interactive relay — copy stdio ↔ pty, applying the host terminal size:

```clojure
(let [p (pty/open-pty!)
      child (pty/spawn-secondary! p ["ssh" "-i" key "-p" port "user@host"])]
  (pty/relay! p child))   ; blocks until the child exits; returns its exit status
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `open-pty!` | — | `{:primary-fd :secondary-fd :secondary-name}` | throws on `posix_openpt`/grant failure |
| `close-pty!` / `close!` | `pty` / `fd` | nil | — |
| `read-fd` | `fd n` | byte array | throws on read failure |
| `write-fd` | `fd b` | bytes written | throws on write failure |
| `make-raw!` | `fd` | previous termios snapshot | — |
| `restore-termios!` | `fd snapshot` | nil | — |
| `capture-termios` | `fd` | termios snapshot | — |
| `redirect!` / `redirect-stdin!` | `src-fd dst-fd` / `fd` | nil | — |
| `dup-fd` | `fd` | new fd | — |
| `get-winsize` / `set-winsize!` | `fd` / `fd rows cols` | `{:rows :cols}` / nil | — |
| `spawn-secondary!` | `pty argv` | Process (stdio on the pty secondary) | — |
| `relay!` | `pty child` | the child's exit status | — |

`relay!` copies bytes between the pty primary and host stdin/stdout until the
child exits, first copying the host terminal size to the pty. The RFC 4254 §8
ECHO=1 handshake is intentionally not ported: it exists only because dirge's
TUI owns `/dev/tty` in raw mode, which a library consumer does not.

termios snapshots are byte-arrays over a fixed 256-byte buffer — generous for
glibc x86_64/aarch64 (60 bytes) and macOS (72 bytes). Constants are resolved
per platform via `os.name`; `TIOCGWINSZ`/`TIOCSWINSZ` are Linux values.

## Status

beta — open/close, raw mode, read/write, spawn, and relay round-trips are
tested. `relay!`'s host-terminal-size application is covered by the relay
test; macOS-specific paths are untested on Linux.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [jolt.ffi](https://github.com/jolt-lang/jolt) — the stdlib FFI layer used here
- [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) — the consumer (SSH attach over an isolated pty)
