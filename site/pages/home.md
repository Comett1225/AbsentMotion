---
title: The home screen
group: Interface
order: 1
summary: Your projects, assets and templates, and the buttons around them.
---
The home screen is the first thing you see after the first start. It lists your projects and has the buttons for creating one, searching and changing settings.

## The top bar

- **Menu button** (top left, three lines). Opens the [Settings](@settings) drawer.
- **AbsentMotion** in the middle.
- **Search** (the magnifier at the right). Opens a search field. While searching, the same place shows an X to close it, and the Back button closes it too.

## The bottom bar

- **Projects** and **Templates** switch between the two pages of the home screen.
- The round **+** button between them creates a new project: see [Creating a project](@new-project).

## Projects and Assets

The Projects page has two chips at the top, **Projects** and **Assets**.

- **Projects** are your videos and animations. Tap a card to open it in the editor.
- **Assets** are reusable pieces that you saved from a project or created with the **ASSET** option of the new project sheet. You add them to other projects from the editor. See [Assets, templates and presets](@assets-templates).

A project and an asset are stored the same way; they are only listed separately.

### What a card shows

Each card has a thumbnail with the project length in the corner, the name, and two lines of facts: the aspect ratio (such as 16:9) and the resolution (such as 1080p), then the file size and the frame rate. The file size is the size of the project file itself. It does not include the photos, videos and audio the project uses, so a project with hours of video can still show a few kilobytes.

The thumbnail is a still taken when you close the editor, at the place where the playhead was. A project you never opened in the editor may show an empty thumbnail.

### The card menu

The three-dot button on a card (named **More**) has these entries.

- **Export** opens the export screen straight from the list. The "Current Frame" options use the first frame. This is also the only place that offers the **XML** export. See [Export](@export).
- **Duplicate** (**Duplicate Asset** for an asset) makes a copy named "name Copy", with its media.
- **Collect media into project** copies every file the project uses from outside its own folder into the project, so it no longer depends on your gallery. See [Projects, backup and import](@projects).
- **Version history** shows earlier versions of the project and lets you go back to one.
- **Delete** (**Delete Asset**) asks for confirmation and then deletes the project, its versions and the media copies nobody else uses. There is no trash: a deleted project cannot be brought back, so make a backup first if you are unsure.

A project whose file is damaged shows **Damaged — restore a version** instead of its facts. Tap it to restore an earlier version, or delete it.

## Search

One search box serves the whole home screen. On the Projects page it looks through project and asset names ("Search projects and assets"); on the Templates page it looks through templates and presets. It ignores capital letters and accents, and every word you type has to appear in the name, in any order.

## Sorting

On the Projects and Assets lists, the header shows **Sort by Date** or **Sort by Name**. Tap it to switch. Date puts the newest first, grouped under date headings. Name sorts from A to Z. The app remembers your choice.

## Templates

The **Templates** page has two chips, **My Templates** and **My Presets**. They are described in [Assets, templates and presets](@assets-templates).

## Importing a project file

By default the home screen has no import button. You can open a project file from another app (a file manager, a message) with Share or Open with, and the app takes it. If you prefer a button, turn on **Show the import card in the project list** in Settings; a card with **Choose file** then appears at the top of the Projects list. See [Projects, backup and import](@projects).

## When something is wrong

- If the app closed unexpectedly, the next start shows **AbsentMotion closed unexpectedly** with the reason and, if there were unsaved edits, an offer to restore them.
- Crash logs are in Settings under **Crash logs**.
- For an outdated version, the app can tell you about a newer one: see [Settings](@settings).
