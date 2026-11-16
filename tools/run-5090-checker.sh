#!/usr/bin/env bash
source "$HOME/.bashrc"
source "$(dirname -- "${BASH_SOURCE[0]}")/runtime-profile.sh"
td18_profile_main checker 5090 "$@"
