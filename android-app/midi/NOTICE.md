# Third-party code

This module is a vendored and modified copy of
[DPMIDI](https://github.com/DisappointedPig/DPMIDI) by DisappointedPig,
licensed under the GNU General Public License v3.0 (see the LICENSE file
in the repository root).

Modifications in this copy include:
- RFC 6295 compliant parsing of the MIDI command section (LEN field,
  delta times, running status, 2-byte commands such as program change)
- One event dispatched per MIDI command instead of per packet
