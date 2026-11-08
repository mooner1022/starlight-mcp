---
title: MCP tools
summary: Giving MCP clients (AI assistants on a PC) tools written in a script: the setup event, input schemas, results, errors, time limits, and connecting a client.
---

The MCP Server plugin runs a [Model Context Protocol](https://modelcontextprotocol.io) server on the device. MCP
clients, such as Claude Code on a PC, connect to it and call tools. Besides its built-in tools, which work on the
projects, a project can add its own, written in its script.

## Registering tools

Allow the event **MCP 도구 설정** (`mcp.tools.setup`) in the project's events, and write its function. It is called
after every compilation, once the tools of the previous compilation are removed:

```js
export function onMcpSetup(server) {
    server.tool("weather", {
        description: "Today's weather in a city",
        inputSchema: { city: "string", days: "integer?" },
    }, async (args) => {
        const r = await fetch(`https://example.com/weather?city=${encodeURIComponent(args.city)}`);
        return await r.json();
    });

    // A description alone: a tool without arguments.
    server.tool("rooms", "The rooms the bot is in", () => ["room 1", "room 2"]);
}
```

- In NeonJS the function must be exported; in Rhino it is a plain function.
- The global `Mcp` is the same object as `server`, for registering from top-level code.
- A `server` kept in a variable can add and remove tools later. After the project is compiled again, an old `server`
  is ignored, with an error in the project log.

## Names and schemas

- Names starting with `starlight_` are the built-in tools' and can't be used.
- When another project registered a tool of the same name first, clients keep seeing that one, and an error goes to the
  project log. This project's tool shows once the other is removed.
- `inputSchema` is a JSON Schema of an object, or a map of argument names to type names: `string`, `number`,
  `integer`, `boolean`, `object`, `array`, `null`. A trailing `?` makes an argument optional, and a value may be a
  schema object instead of a type name. Without `inputSchema`, the tool takes no arguments.
- `options` may also have `title` and `annotations`, which are sent to clients as they are.

## Results and errors

- The handler gets the client's arguments as one object (`args.city`). In NeonJS it is a real JS object, so arrays
  have `map` and the rest; in Rhino it is a Java `Map` (`args.get("city")`).
- Strings, numbers and booleans become text. Other objects and arrays become JSON text.
- An object with a `content` array (`{ content: [...], isError?, structuredContent? }`) is sent as the MCP result as it
  is, for images and other content types.
- Throwing fails the call with the error message, which also goes to the project log.
- Handlers run on the project's threads. A call that takes longer than the time limit fails; the limit is the plugin's
  **프로젝트 도구** setting, 60 seconds by default. In NeonJS a handler may return a promise.
- Calls fail while the project is off or not compiled.

## Connecting a client

The server address is `http://<device IP>:8765/mcp` by default; the plugin's settings (**제어 › 서버 상태**) show it
and copy it on a tap. Clients send the access token as `Authorization: Bearer <token>`:

```sh
claude mcp add --transport http starlight http://192.168.0.2:8765/mcp --header "Authorization: Bearer <token>"
```

- The access token is required by default; StarLight asks for it in the plugin's initial setup. Without one, every
  request is rejected.
- **접속 허용 범위** chooses all networks (`0.0.0.0`) or this device only (`127.0.0.1`, reachable from a PC with
  `adb forward tcp:8765 tcp:8765`).
- The built-in tools (`starlight_list_projects`, `starlight_read_file`, `starlight_compile_project`, ...) let clients
  read, edit and compile projects. **StarLight 내장 도구** in the security settings turns them off, or limits them to
  reading.
