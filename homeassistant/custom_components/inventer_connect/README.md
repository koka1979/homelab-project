# inVENTer Connect — Home Assistant integration

Local control of an inVENTer Connect ventilation controller over Bluetooth LE.
No cloud, no vendor account: the integration speaks the same protocol the
inVENTer Mobile app speaks.

The protocol was recovered from the app; the analysis is in
[`docs/research/inventer-connect-reverse-engineering.md`](../../../docs/research/inventer-connect-reverse-engineering.md).

## What it talks to

The controller, not the individual ventilation units. The controller keeps its
own 868 MHz network to the inner dampers, so this integration takes the app's
place towards the controller and leaves the radio side untouched.

Tested shape: Basic Connect e4/e8 or easy connect e16 without WiFi. Controllers
with WiFi expose the same packet protocol over TLS-PSK on TCP 47820, which this
integration does not implement yet.

## Related work

For standalone fans that use the Pax per-characteristic GATT profile — Pax
Calima and Levante, Vent-Axia Svara and Svensa, and reportedly the inVENTer
Pulsar — use [`eriknn/ha-pax_ble`](https://github.com/eriknn/ha-pax_ble)
instead. It is mature, installable through HACS, and covers those devices
properly.

This integration exists for the case that one does not cover: the Connect
controller, which speaks a packet protocol over a single characteristic rather
than the Pax profile. As of September 2026 nothing published handles it.

## Requirements

- The controller in range of a Bluetooth adapter or an ESPHome Bluetooth proxy
  with active connections enabled.
- The PIN printed in the controller's manual.

## Installation

Copy `custom_components/inventer_connect` into your Home Assistant `config`
directory, restart, then add the integration. Discovered controllers appear
automatically; otherwise add it manually and pick the device from the list.

## Entities

| Entity | Notes |
|---|---|
| `fan` | Speed 1-4, preset modes `auto`, `boost`, `pause` |
| `sensor` | Indoor/outdoor temperature and humidity, CO2, VOC, fan speed, override remaining |
| `binary_sensor` | Boost active, pause active, timer active, global command active |

Outdoor and CO2 sensors report unavailable unless the zone's status flags say
the matching sensor is fitted.

`auto` cancels any override and hands the zone back to its programmed
ventilation profile. Setting a speed sends an override with a timeout of two
poll intervals, so the zone returns to its profile by itself if Home Assistant
stops talking to it.

## Connection handling

By default the link is dropped between polls, because the controller is not
expected to accept the app and Home Assistant at once. The *Hold the Bluetooth
connection open* option trades that back for faster commands.

Whether the controller accepts concurrent connections at all is untested — if
the inVENTer app stops connecting while this integration runs, that is why.

## Status

The command path is derived from the app's own request builders and is
well understood. The status path decodes the zone row at the offsets the app
uses; several bytes in that row are still unidentified.

If something reads wrong, the integration's diagnostics download includes the
raw zone-row packet as hex — that is what the remaining fields can be mapped
from.

## Development

The protocol layer has no Home Assistant or Bluetooth imports and is tested
standalone:

```bash
python3 -m pytest homeassistant/tests/test_protocol.py
```
