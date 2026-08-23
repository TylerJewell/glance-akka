# Acknowledgements

This project is a port of **[glanceapp/glance](https://github.com/glanceapp/glance)**.

## Licence

glance is under the **GNU Affero General Public License, version 3**
(`glance/LICENSE`, read rather than taken from a badge). The repository carries no
per-file copyright headers and no `AUTHORS` file; its commits are authored by Svilen
Markov and its contributors.

This port **ships parts of glance verbatim** — see below — so it is a derived work and
carries the same licence. `LICENSE` in this repository is glance's own AGPL-3.0 text,
copied unchanged. The repository is private; making it public is a separate decision,
and under the AGPL that decision comes with the obligation to offer the corresponding
source to anyone the service is offered to over a network.

## Copied verbatim

Every file listed here was taken from a running glance and is unchanged except where
this section says otherwise. The list was produced by `python toolkit/copied_strings.py
glance`, which pulls every string of ten characters or more out of the rebuild and finds
the ones that also occur in the clone — not from memory.

| File | What it is | Changed? |
|---|---|---|
| `src/main/resources/static/css/bundle.css` | glance's stylesheet, as its own server assembles and serves it | Line endings normalised to `\n`. Nothing else. |
| `src/main/resources/static/js/popover.js` | the pop-up helper the page shell uses | Line endings only. |
| `src/main/resources/static/js/masonry.js` | the column layout the page shell uses | Line endings only. |
| `src/main/resources/static/js/utils.js` | small helpers the page shell uses | Line endings only. |
| `src/main/resources/static/js/templating.js` | element helpers the page shell uses | Line endings only. |
| `src/main/resources/static/fonts/JetBrainsMono-Regular.woff2` | the page's typeface, as glance serves it | Unchanged. |
| `src/main/resources/static/js/page.js` | the page's own script | **Changed.** One function replaced: where it fetched the page's content over HTTP it now reads the content embedded in the document and subscribes to an event stream. Six lines added inside `setupPage` to redraw when a frame arrives. Everything else — the theme picker, the collapsible lists, the relative-time labels, the search boxes, the carousels — is glance's, unchanged. |
| `src/main/resources/page.html` | the page shell glance renders around its content | **Changed.** Four values replaced by placeholders the port fills in (`{{SLUG}}`, `{{TITLE}}`, `{{NAV}}`, `{{CONTENT}}`), one `<template>` element added to carry the first render's content, and the web-app manifest link removed. The markup, the inline logo, the theme picker and the mobile navigation are glance's. |

Declared wholesale, so that the check reads them as copied rather than asking for a
sentence per string inside them:

    Verbatim-copy: src/main/resources/static/
    Verbatim-copy: src/main/resources/page.html
    Verbatim-copy: src/test/resources/original/

## Reproduced, not copied

The widget markup is a transcription rather than a copy: glance renders it from Go
templates, which do not run on this stack, so
`src/main/java/io/akka/glance/domain/WidgetRenderer.java` and `PageRenderer.java` emit the
same bytes from Java. They were held to fragments cut out of the running original's own
response — `src/test/resources/original/*.html` — which are themselves glance's output and
are copied.

The messages a widget shows are glance's own words, reproduced so that a page reads the
same: `failed to retrieve any content`, `failed to retrieve some of the content: missing
%d RSS feeds`, `No items were returned from the feeds.`, and the `ERROR` heading. Each was
read off the original's rendered response, not from memory.

The CSS class names, element structure and `data-` attribute names in the rendered markup
are glance's, necessarily: the port reuses glance's stylesheet, and a stylesheet only lays
out the markup it was written for. `copied_strings.py` names fourteen shorter strings that
occur in both codebases, and they fall into three kinds:

- **Markup fragments in `WidgetRenderer.java`** — `<ul class="list list-gap-14
  collapsible-container" data-collapse-after="`, `            <li
  data-dynamic-relative-time="`, ` fill="none" viewBox="0 0 24 24" stroke-width="1.5">`,
  and the closing tags `    </li>`, `        </ul>`, `    </div>` and `
    </div>
`.
  These are glance's markup, reproduced deliberately and to its whitespace: the port
  serves glance's stylesheet, and a class name or an indent that differed would lay the
  page out differently. The `viewBox` fragment is the path of glance's own warning icon.
  Reproduced here exactly as they appear in the rebuild, indentation and line breaks
  included, because that is what makes them the same markup:

<pre>
            <li data-dynamic-relative-time="
        </ul>

    </li>

 fill="none" viewBox="0 0 24 24" stroke-width="1.5">

<ul class="list list-gap-14 collapsible-container" data-collapse-after="
</pre>

- **Route paths** — `/api/set-theme/{key}` is glance's route, answered so that its theme
  picker does not report a failure; `/api/widgets` is this port's own and shares the
  phrase with glance's configuration vocabulary by coincidence.
- **HTTP header names** — `If-None-Match`, `If-Modified-Since`, `Last-Modified`,
  `User-Agent` and `Content-Type`. These are the names in RFC 9110. Two programs speaking
  the same protocol share its vocabulary; neither took them from the other.

## Derived, with nothing copied

The scheduler, the cache-deadline arithmetic, the retry backoff, the outcome classifier,
the feed merge and the page refresh pass are reimplementations. No Go source was
translated line by line; each rule was established by running the original and recorded in
`glance-port/docs/question-log.md` before any of this was written. The behaviour is
derived from glance and this port would not exist without it.

## Also used

- Akka, and the Akka Java SDK.
