#!/usr/bin/env bash
source "$HOME/.bashrc"
source "$(dirname -- "${BASH_SOURCE[0]}")/rtx5090-profile.sh"
td18_5090_main emitter "$@"
