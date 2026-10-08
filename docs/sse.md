# SSE for Datastar in Pedestal (http-kit)

Findings on serving Datastar-style Server-Sent Events from this app, which runs
Pedestal 0.8.1 on the http-kit connector via genegraph-framework.

**Summary:** short-lived SSE responses work through Pedestal on http-kit as-is.
Long-lived streams need care, because in testing the server never detected the
client disconnecting.

Tests were run on 2026-10-02 against standalone Pedestal 0.8.1 + http-kit
servers with curl. They have **not** yet been run through genegraph-framework's
processor chain or with a real browser.

## What works

**Pedestal's `io.pedestal.http.sse/start-stream` works with the http-kit
connector.** Pedestal coordinates the header write with http-kit, so headers go
out before any events. A test endpoint returned:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=UTF-8
Cache-Control: no-cache
Transfer-Encoding: chunked

event: datastar-patch-signals
data: signals {"n":0}
```

That is the event format Datastar expects. `start-stream` returns the context
with `:response` set immediately, so it should fit inside a framework processor
endpoint like any other interceptor.

**Calling http-kit's `as-channel` inside a Pedestal interceptor also streams
correctly.** Pedestal passes `AsyncChannel` bodies straight through for this
purpose (see `io.pedestal.http.http-kit.response`). That suggests Datastar's
Clojure SDK http-kit adapter, which uses `as-channel`, could work too. The SDK
itself has not been tried.

## Problems found

### 1. Multi-line HTML breaks the event format (easy to fix)

Pedestal splits event data on newlines into multiple `data:` lines, but
Datastar needs the `elements ` prefix on every line:

```
data: elements <div id="a">
data:   <p>multi</p>        ← missing "elements "
```

Helpers that produce correct events (cheshire is already a dependency):

```clojure
(defn patch-elements [html]
  {:name "datastar-patch-elements"
   :data (->> (str/split-lines html)
              (map #(str "elements " %))
              (str/join "\n"))})

(defn patch-signals [m]
  {:name "datastar-patch-signals"
   :data (str "signals " (json/generate-string m))})
```

### 2. Client disconnects were not detected

After curl disconnected:

- Pedestal's `:on-client-disconnect` callback never fired.
- `hk/open?` on the request's async channel stayed `true` for 30+ seconds.
- An `as-channel` `:on-close` handler never fired.
- **Plain http-kit with no Pedestal behaved the same way.**

So this comes from http-kit, or possibly from the local loopback test setup,
not from Pedestal. It still needs checking with a real browser closing a tab.
Until that's confirmed, assume a stream whose browser has gone away keeps
running.

## Design implications

- **Default to plain `text/html` responses.** Datastar merges returned
  elements by `id` without any SSE. That covers most interactions, and the
  disconnect question doesn't arise.
- **SSE for multi-step responses is fine.** For example: send a loading state,
  run a query, send the result, close. These streams end by themselves, so a
  missed disconnect costs at most a few seconds of work.
- **Long-lived streams need limits.** Examples are live updates as Kafka events
  arrive. Cap their lifetime, have each producer check whether its writes are
  still wanted, and keep the cost of an abandoned stream low. Datastar's retry
  behavior can then reopen a capped stream from the browser.
- **Don't keep a Jena transaction open while streaming.**
  `jena-transaction-interceptor` closes its read transaction when the
  interceptor chain finishes, which happens before a stream's background thread
  sends anything. Each render inside a stream needs its own `rdf/tx`.
- **Read Datastar's signals yourself.** Datastar sends them as JSON in the
  `datastar` query parameter on GET and in the request body on POST. The
  framework's default interceptors have `query-params` and `body-params`
  commented out, so either parse `:query-string` directly or add those
  interceptors to the UI processors.

## SDK or hand-rolled?

Hand-rolled is recommended: the two helpers above plus `sse/start-stream`,
roughly 30 lines in total. That avoids depending on the Datastar SDK working
inside Pedestal's interceptor chain.

## Next step

Build one processor endpoint that streams two `patch-elements` events with a
Jena read in between, and open it from a real page. That validates the
framework integration, the transaction handling, and browser disconnect
behavior.
