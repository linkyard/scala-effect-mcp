# Conformance Server

A server for the official MCP conformance suite, [`@modelcontextprotocol/conformance`](https://github.com/modelcontextprotocol/conformance). It is a Scala port of the reference server of the suite (`examples/servers/typescript/everything-server.ts`) and serves tools, resources, a resource template, prompts and completions that the scenarios expect.

Sources: [ConformanceMcpServer.scala](src/main/scala/ch/linkyard/mcp/example/conformance/ConformanceMcpServer.scala) (main), [ConformanceServer.scala](src/main/scala/ch/linkyard/mcp/example/conformance/ConformanceServer.scala), [ConformanceTools.scala](src/main/scala/ch/linkyard/mcp/example/conformance/ConformanceTools.scala), [ConformancePrompts.scala](src/main/scala/ch/linkyard/mcp/example/conformance/ConformancePrompts.scala), [ConformanceResources.scala](src/main/scala/ch/linkyard/mcp/example/conformance/ConformanceResources.scala).

The server is available under <http://127.0.0.1:3000/mcp>. The port is the first argument or the environment variable `PORT`.

```shell
sbt "exampleConformance/run"          # or: sbt "exampleConformance/run 3001"
```

## Running the suite

The suite is not part of the CI build, because `npx` downloads it. Run it by hand or with the script, which starts the server, runs the suite for the revisions 2026-07-28 (twice), 2025-11-25 and 2025-06-18 with the baseline and stops the server:

```shell
example/conformance/run-conformance.sh
```

Requirements: sbt, java, node with npx. `PORT`, `SUITE` (the npm package and its version), `OUT_DIR` (the results, one `checks.json` per scenario) and `SERVER_URL` (test a server that is already running) are optional environment variables. The exit code is not 0 if a scenario fails that is not in the baseline or if a baselined scenario passes (remove the entry then).

To run a single scenario against a running server:

```shell
npx @modelcontextprotocol/conformance@0.2.0-alpha.12 server --url http://127.0.0.1:3000/mcp \
  --spec-version 2026-07-28 --scenario server-stateless --expected-failures example/conformance/expected-failures.yml
```

The script runs:

| Run | Options | What it checks |
|---|---|---|
| 2026-07-28 | `--suite all --spec-version 2026-07-28` | the active, draft and pending scenarios of the stateless revision |
| 2026-07-28 requirements | `--requirements 2026-07-28` | exactly what the specification requires (the tasks extension is run but not scored) |
| 2025-11-25 | `--spec-version 2025-11-25` | the initialize handshake and the session of legacy clients |
| 2025-06-18 | `--spec-version 2025-06-18` | same for the oldest supported revision |

The pending suite of the old revisions (`--suite pending`) is covered by the baseline as well, but not run by the script.

Versions that were used: `@modelcontextprotocol/conformance` 0.2.0-alpha.12 (alpha line), node 22.

## Baseline

[expected-failures.yml](expected-failures.yml) lists the checks that fail because the library does not have a feature, with the reason and the quoted text of the specification. Nothing in it hides a bug.

| Entry | Reason |
|---|---|
| `input-required-result-basic-sampling:sep-2322-sampling-incomplete`, `tools-call-sampling` | sampling is not supported (deprecated since 2026-07-28) |
| `input-required-result-basic-list-roots:sep-2322-list-roots-incomplete` | roots are not supported (deprecated) |
| `input-required-result-multiple-input-requests:sep-2322-multiple-inputs-incomplete` | needs input requests for sampling and roots next to elicitation |
| `input-required-result-capability-check:sep-2322-respect-client-capabilities` | the scenario declares only sampling and expects an input request for it |
| `server-stateless:sep-2575-server-no-log-without-loglevel`, `tools-call-with-logging` | logging is not supported (deprecated), the diagnostic tools are missing |
| `server-sse-polling` | resumable SSE streams (event ids, `Last-Event-ID`) are not supported |
| `tasks-*` | the tasks extension is not supported |

The elicitation scenarios of the earlier revisions (`tools-call-elicitation`, `elicitation-sep1034-defaults`, `elicitation-sep1330-enums`) pass: the library asks legacy clients with `elicitation/create` and retries the call with the answer.

## What the server does

* `test_*` tools for the content types, progress, errors, the multi round trip scenarios (`Outcome.elicit`, request state) and the elicitation of legacy clients (forms with raw JSON schemas for defaults and enums).
* `json_schema_2020_12_tool` returns a JSON schema 2020-12 that must not be changed by the server.
* `test_custom_header_params` has a parameter with `x-mcp-header` (`Mcp-Param-Region`).
* `test_trigger_tool_change` and `test_trigger_prompt_change` fire the list changes for open subscriptions (`subscriptions/listen`).
* `test_missing_capability` fails with the error `-32021` for clients without the sampling capability.
* Resources `test://static-text`, `test://static-binary`, `test://watched-resource` (updated every 3 seconds for subscribers), the template `test://template/{id}/data` and the prompts `test_simple_prompt`, `test_prompt_with_arguments`, `test_prompt_with_embedded_resource`, `test_prompt_with_image` and `test_input_required_result_prompt`.
* Lists are cached for 5 minutes (public).
