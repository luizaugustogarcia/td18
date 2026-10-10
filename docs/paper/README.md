# Paper draft

`main.tex` is the working paper for the E12-to-E18 extremal-class reduction.
It deliberately distinguishes the implemented pipeline from the still-pending
large computational proof artifacts.

Build it with:

```bash
latexmk -pdf main.tex
```

The draft currently uses a portable `article` layout. Before submission, move
it to the SIAM Journal on Discrete Mathematics class used by the companion
TD(16) paper and update the computational-status section with the archived
stage manifests, checksums, counts, and hardware record.
