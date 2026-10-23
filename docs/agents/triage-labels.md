# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual strings used in this repo's issue tracker.

Because this repo tracks issues as local markdown under `.scratch/`, each role is recorded as a `Status:` line near the top of the issue file rather than as an issue-tracker label. The strings below are the values that line takes.

| Label in mattpocock/skills | Value in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding string from this table in the `Status:` line.

Edit the "Value in our tracker" column to match whatever vocabulary you actually use.
