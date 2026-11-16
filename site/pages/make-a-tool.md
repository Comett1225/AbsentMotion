---
title: Make a tool
group: Help
order: 4
site_only: true
summary: How to write a custom tool for AbsentMotion: the tool.json file, its inputs and steps, the limits, and how to share it.
---
A custom tool is a small file that describes a form and a list of steps. The steps use things AbsentMotion can already do, such as trimming a video, finding beats or adding a text layer. No code runs, so a tool cannot reach the internet or read other files. The same tool works on Android, Mac, Windows and Linux.

People add tools from **Community**, **Tools**. Before adding, the app shows what the tool can do. An added tool appears in **Tools** on the home screen, in the editor under **+** and **Tools**, or in both. AbsentMotion draws the form itself, so a custom tool looks like a built-in one. The form also has **Update** and **Remove**.

## The files

A tool is a folder with two files:

- `tool.json`: the tool, at most 64 KB.
- `icon.png`: a square PNG from 64 to 512 pixels, at most 256 KB.

The folder name and the tool's `id` are the same.

## tool.json

| Field | What it holds |
|---|---|
| `id` | 1 to 48 of a-z, 0-9 and -. The same as the folder name. |
| `name` | The name shown on the card, up to 40 characters. |
| `version` | Like 1.0.0. Change it with every update. |
| `api` | 1. Apps that do not know the api hide the tool. |
| `minVersion` | Optional. The lowest AbsentMotion version code that may use the tool. |
| `creator` | Who made it. |
| `licence` | Optional, for example CC BY 4.0. |
| `description` | One or two sentences, up to 500 characters. |
| `icon` | Optional. Always icon.png. |
| `runs` | recipe |
| `where` | home, editor or both. |
| `permissions` | What the tool can do. See the table below. |
| `inputs` | The fields of the form, at most 16. |
| `steps` | What the tool does, 1 to 32 steps, run in order. |

## Inputs

Each input has an `id` (a letter, then letters, digits or _), a `type` and a `label`. Most types also take a `default`.

| Type | Shows | Extra fields |
|---|---|---|
| `video` | A card to choose a video | none |
| `image` | A card to choose a picture | none |
| `text` | A text field | `maxLength` (1 to 500) |
| `number` | A number field | `min`, `max`, `step`, `unit` |
| `choice` | A row of buttons | `options`: text, or objects with `label` and `value`. `default` is the number of an option, from 0. |
| `switch` | A switch | none |
| `colour` | A colour like #ff8800 | none |
| `layer` | The layers of the open project | none, editor only |
| `preset` | The user's saved presets | none, editor only |

The value of a choice is the `value` of the chosen option. Options written as plain text give the number of the option.

## Values

A step field takes a fixed value, or the value of an input written as `$name`. Steps that make something can give it a name with `out`. Later steps use that name, for example `"in": "part"`. Times are seconds.

## Steps

| Step | Fields | Makes | Needs |
|---|---|---|---|
| `trim` | `in` (video), `start`, `length` (optional) | video | media.read, media.write |
| `compress` | `in` (video or picture), `level`: low, medium or high | the same kind | media.read, media.write |
| `extractAudio` | `in` (video), `format`: m4a or mp3 | sound | media.read, media.write |
| `gif` | `in` (video), `start`, `length` (at most 30), `size` (64 to 720), `fps` (1 to 30), `loop` | GIF | media.read, media.write |
| `beats` | `in` (video or layer) | a list of times | media.read |
| `every` | `list`, `step` (1 to 64), `offset` | a list of times | nothing |
| `addMedia` | `in` (video, picture or sound) | a layer | project.write |
| `addText` | `text`, `colour`, `length` | a layer | project.write |
| `setProperty` | `layer`, `property`, `value` | nothing | project.write |
| `addKeyframe` | `layer`, `property`, `at`, `value` | nothing | project.write |
| `applyEffect` | `layer`, `effect` (an id like absent.blur), `values` | nothing | project.write |
| `applyPreset` | `layer`, `preset` | nothing | project.write |
| `split` | `layer`, `at` (seconds or a list of times) | nothing | project.write |
| `speed` | `layer` (video or sound), `value` (0.05 to 100) | nothing | project.write |

- `property` is opacity (0 to 100), scale (in %), rotation (in degrees), position (`[x, y]` in project pixels) or colour.
- `setProperty` sets the value at the playhead, as the editor does.
- `at` in `addKeyframe` counts from the start of the layer. A negative number counts back from its end.
- `beats` gives times from the start of the video or layer, and `split` cuts at times from the start of the layer.
- `values` in `applyEffect` names settings of the effect, as in the effect's JSON file.
- `addMedia` and `addText` place the new layer at the playhead.

## Permissions

| Permission | The app says |
|---|---|
| `media.read` | Read the videos and pictures you choose |
| `media.write` | Make new files you can save or share |
| `project.write` | Change the open project, as one step you can undo |

A tool must name every permission its steps need, and no more. A tool with `project.write`, a `layer` input or a `preset` input works only in the editor, so its `where` is `["editor"]`.

## What a tool cannot do

- It cannot use the internet.
- It reads only the files the user chooses, and writes only to its own folder for results.
- It has no loops and no sums. Script tools for that are planned.
- All changes to the project happen at the end, as one step on the undo list. Cancel stops the tool, and the project stays as it was.
- The app checks the whole tool before it runs. A tool with an unknown step, input or permission, a wrong value or a broken limit does not run, and the app says why.

## Example

This tool cuts part of a video and keeps its sound:

```
{
 "id": "sound-bite",
 "name": "Sound bite",
 "version": "1.0.0",
 "api": 1,
 "creator": "Your name",
 "description": "Cuts a short part out of a video and keeps only its sound, as M4A or MP3.",
 "icon": "icon.png",
 "runs": "recipe",
 "where": ["home", "editor"],
 "permissions": ["media.read", "media.write"],
 "inputs": [
  {"id": "video", "type": "video", "label": "Video"},
  {"id": "start", "type": "number", "label": "Start at", "min": 0, "max": 3600, "step": 0.5, "default": 0, "unit": "s"},
  {"id": "length", "type": "number", "label": "Length", "min": 1, "max": 120, "step": 1, "default": 10, "unit": "s"},
  {"id": "format", "type": "choice", "label": "Format", "options": [{"label": "M4A", "value": "m4a"}, {"label": "MP3", "value": "mp3"}], "default": 1}
 ],
 "steps": [
  {"op": "trim", "in": "video", "start": "$start", "length": "$length", "out": "part"},
  {"op": "extractAudio", "in": "part", "format": "$format", "out": "sound"}
 ]
}
```

More examples are in the tools folder of the AbsentMotion Community repository.

## Share a tool

1. Put the folder in `tools/` in a copy of the Community repository and run `python3 scripts/community.py add tools/<id>`. It checks the tool the same way the app does.
2. Send the folder on Discord or through the report form linked in the app's About screen.
3. Only the maintainer adds tools to the Community repository.
