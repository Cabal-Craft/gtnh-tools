# gtnh-tools

Tools for GregTech: New Horizons. Each tool lives in its own folder and is published to GitHub Pages under its own path.

| Tool | Folder | What it is |
| --- | --- | --- |
| Dust Atlas | [`dust-atlas/`](dust-atlas/) | Every dust as a clickable flowchart, plus an all-dusts list and per-machine recipe pages. Served at `/dust-atlas/`. |

## Layout

```
index.html                 landing page (site root)
.github/workflows/pages.yml  deploys index.html + every tool's site/ folder
<tool>/                    one folder per tool, self-contained
```

To add a tool: create its folder, then add one `cp -r <tool>/site _site/<tool>` line to `pages.yml` and a link to `index.html`.
