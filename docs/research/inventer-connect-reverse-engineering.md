# inVENTer Mobile — Reverse Engineering für Home Assistant

Analyse der Android-App `de.inventer.mobile` (Version 5.0.1, `versionCode` 50000001,
MD5 `cc06c0f3d8a677051807e5ce9a559f57`, signiert `CN=inventer, O=inventer, C=de`)
mit dem Ziel, einen inVENTer-Lüfter aus Home Assistant zu steuern.

Zweck ist Interoperabilität mit selbst betriebener Hardware. In der EU ist die
Dekompilierung dafür durch Art. 6 der Software-Richtlinie 2009/24/EG bzw. § 69e UrhG
gedeckt. Es werden keine Binaries oder dekompilierten Quellen in dieses Repository
übernommen — nur Protokollbeobachtungen.

## Ergebnis in einem Satz

Machbar, und deutlich besser als erwartet: die App spricht **ausschließlich lokal**
(kein Cloud-Backend, keine einzige Hersteller-URL im Bytecode), und die relevante
SDK-Schicht liegt **unobfuskiert** im APK.

## Aufbau der App

Die App ist nativ (Kotlin/Java, Realm als lokale DB), kein Flutter/RN. Der Anwendungscode
unter `de.inventer.mobile` ist per R8 obfuskiert, aber die gesamte Geräteanbindung liegt
unobfuskiert in `com.volution.wrapper.acdeviceconnection` (369 Klassen). inVENTer gehört
zur Volution Group, die dasselbe SDK für mehrere Marken verwendet — deshalb enthält das
APK Gerätefamilien, die mit inVENTer nichts zu tun haben.

Mitgelieferte Firmware-Images unter `assets/` benennen die Zielsysteme:
`FCU_Update.bin` (Fan Control Unit), `MZCU_Update.bin` (Multi-Zone Control Unit),
`Sensors_Update.bin`, `mev_update.bin`.

Gerätefamilien im SDK: `Calima`, `Momento`, `Sky`, `Magna`, `Mev`, `XFLP`, `Hyper`,
`Zirconia`. Für inVENTer Connect ist **Zirconia** die relevante Familie (Beleg:
`ZirconiaRequestWiFiModeWrite`, Zonen-/MEV-Kommandos, Multi-Zonen-Begriffe passend
zu MZCU). Die Zuordnung Zirconia ↔ „easy connect e16 WiFi" ist aus Funktionsumfang
und Firmware-Assets erschlossen, nicht aus einem expliziten Namens-String — sie muss
am realen Gerät bestätigt werden.

## Weg A — WLAN (bevorzugt, sofern ein WiFi-Regler vorhanden ist)

Drei Schritte, alle im lokalen Netz:

**1. Discovery — MDSDP über UDP-Broadcast, Port 47818**

Suchpaket ist 8 Byte: ASCII `MDSDP` + `0x03` (MSG_SEARCH) + 16-Bit-Transaktions-ID
little-endian. Antwort ist ein 94-Byte-Announce mit IPv4-Adresse, 16-Byte-Geräte-UUID,
Service-Type-UUID sowie Namensfeldern (Device Name 64, Domain 32, Location 32 Byte).
Nachrichtentypen: `ANNOUNCE_V4=0`, `ANNOUNCE_V6=1`, `LEAVING=2`, `SEARCH=3`;
Request-Flag `0x80`, Not-implemented-Flag `0x40`, Typmaske `0x3F`.

**2. Transport — TCP Port 47820 mit TLS-PSK**

Kein Zertifikat, sondern Pre-Shared Key. Client bietet genau zwei Cipher Suites an:
`0x008C` (TLS_PSK_WITH_AES_128_CBC_SHA) und `0x008D` (TLS_PSK_WITH_AES_256_CBC_SHA),
dazu die Max-Fragment-Length-Extension (Wert 1 = 512 Byte).

Die **PSK-Identity ist fest `"12345"`**. Der PSK selbst ist ein String, den die App
**einmalig über BLE vom Regler ausliest** und danach lokal speichert:

- Service `e6834e4b-7b3a-48e6-91e4-f1d005f564d3`
- Characteristic `638ff62c-3823-4e0f-8179-1695c46ee8ad` (PSK)
- Characteristic `98faf8a5-1ee6-4b0c-911e-dc37bff5206f` (Geräte-UUID)
- PIN-Handshake über `4cad343a-209a-40b7-b911-4d9b3df569b2` (PIN schreiben)
  und `d1ae6b70-ee12-4f6d-b166-d2063dcaffe1` (Bestätigung lesen)

Das ist der eigentliche Schlüssel zur Integration: der PSK muss genau **einmal**
ausgelesen werden (per `bluetoothctl`/`gatttool`, oder indem man ihn aus den
SharedPreferences einer bereits gekoppelten App-Installation zieht), danach ist die
WLAN-Verbindung dauerhaft ohne Bluetooth nutzbar.

**3. Protokoll — Zirconia-Paketformat**

Paket beginnt mit Magic `0xA5`, dann Typ (`STD_TYPE_*`), Com-Type, Zieladresse
(`0` = Regler, `159` = ESP32-Modul), Payload und Tail. Zeitstempel sind Sekunden
seit `2000-01-01 00:00:00`.

Lüfter setzen (`STD_TYPE_USER_OVERRIDE`, ComType 30) trägt eine 8-Byte-Payload,
little-endian:

| Offset | Größe | Feld |
|---|---|---|
| 0 | 1 | commandType |
| 1 | 1 | fanSpeed |
| 2 | 1 | fanMode |
| 3 | 1 | zoneID |
| 4 | 4 | timeoutSec |

`commandType`: `0` none, `1` global boost, `2` global pause, `3` zone boost,
`4` zone pause, `5` zone speed/mode, `6` zone vent profile, `7` cancel.

Weitere implementierte Kommandos (Auswahl aus 52 Request-Klassen): Boost, Pause,
Shutdown, Cancel, Zonenname/-schwelle, Lüftungsprofile inkl. Zeitslots, Silent Hours,
Filterwartungsintervall, Zeitsynchronisation, Geräte-RSSI, WLAN-Konfiguration,
Device-View-Abfragen (Status je Gerätezeile) sowie Firmware-Update.

Status lesen läuft über `DeviceViewHeader` + `DeviceViewPacketForRow` — Kopfpaket
liefert die Zeilenzahl, danach wird je Gerät eine Zeile abgeholt.

## Weg B — Bluetooth LE direkt

Falls kein WiFi-Regler vorhanden ist oder einzelne Lüfter direkt angesprochen werden
sollen. Das SDK benutzt hier das Pax-GATT-Profil (Volution besitzt auch Pax), dessen
Ältere Varianten in der Home-Assistant-Community bereits umgesetzt sind — als
Referenzimplementierung für eigene Arbeit brauchbar.

Services: Config `c119e858-0531-4681-9674-5a11f0e53bb4`,
Connection `e6834e4b-7b3a-48e6-91e4-f1d005f564d3`,
Status `1a46a853-e5ed-4696-bac0-70e346884a26`.

Wesentliche Characteristics:

| Funktion | UUID |
|---|---|
| Fan / Lüfterstufe | `7c4adc02-2f33-11e7-93ae-92361f002671` |
| Fan settings | `7c4adc07-2f33-11e7-93ae-92361f002671` |
| Humidity / System data | `7c4adc01-2f33-11e7-93ae-92361f002671` |
| Silent hour | `7c4adc03-2f33-11e7-93ae-92361f002671` |
| Silent hours enable / RTC | `7c4adc06-2f33-11e7-93ae-92361f002671` |
| Fan test | `7c4adc04-2f33-11e7-93ae-92361f002671` |
| User trigger | `7c4adc08-2f33-11e7-93ae-92361f002671` |
| Boost | `118c949c-28c8-4139-b0b3-36657fd055a9` |
| Mode | `90cabcd1-bcda-4167-85d8-16dcd8ab6a6b` |
| Basic ventilation | `faa49e09-a79c-4725-b197-bdc57c67dc32` |
| Automatic cycles | `f508408a-508b-41c6-aa57-61d1fd0d5c39` |
| Night mode | `b5836b55-57bd-433e-8480-46e4993c5ac0` |
| Sensor data | `528b80e8-c47a-4c0a-bdf1-916a7748f412` |
| Sensitivity | `e782e131-6ce1-4191-a8db-f4304d7610f1` |
| Level of fan speed | `1488a757-35bc-4ec8-9a6b-9ecf1502778e` |
| Clock | `6dec478e-ae0b-4186-9d82-13dda03c0682` |
| Time functions | `49c616de-02b1-4b67-b237-90f66793a6f2` |
| Status | `25a824ad-3021-4de9-9f2f-60cf8d17bded` |
| Reset | `ff5f7c4f-2606-4c69-b360-15aaea58ad5f` |

Für die Multi-Zonen-Variante (Momento) gilt Service `75430500-d8c7-11e6-88f3-8fb4792b4153`
mit Slot-basierten Characteristics (`...0507` Slot-Auswahl, `...0508` Slot-Daten,
`...0509` Slot-Name, `...0503` Heizstufe, `...050e` Mode).

Einschränkung: BLE bindet einen Bluetooth-Adapter dauerhaft an einen Lüfter und ist
reichweitenkritisch. Für einen dauerhaften HA-Betrieb ist ein ESPHome-BLE-Proxy in
Reichweite des Bads die realistische Bauform.

## Weg C — 0–10 V am sMove-Regler

Wenn es ein älteres System mit sMove-Regler ist, ist Reverse Engineering überflüssig.
Der sMove hat einen 0–10-V-Steuereingang mit dokumentierten Spannungsfenstern
(ca. 0 V Abluft St. 3, 1 V Abluft St. 4, 2 V Abluft St. 2, 3 V Abluft St. 1,
4 V aus, 5–8 V Wärmerückgewinnung St. 1–4; Eingangsimpedanz ca. 6,4 kΩ). Ein
Shelly-Dimmer oder ein 0–10-V-Modul genügt — das ist der übliche Community-Weg und
in ein bis zwei Stunden erledigt.

## Empfohlenes Vorgehen

1. Hardware bestimmen: welcher Regler hängt am Bad-Lüfter (sMove s4/s8,
   Basic Connect e4/e8, easy connect e16, e16 WiFi)?
2. Bei sMove → Weg C, fertig.
3. Bei Connect mit WiFi-Regler → Weg A. Reihenfolge: UDP-Discovery mit
   `inventer-mdsdp-discover.py` in diesem Verzeichnis bestätigen (schnellster
   Machbarkeitstest, kein BLE nötig),
   dann PSK per BLE auslesen, dann TLS-PSK-Handshake gegen Port 47820, dann
   Device-View lesen, zuletzt Schreibkommandos.
4. Bei Connect ohne WiFi → Weg B mit ESPHome-BLE-Proxy.
5. Anbindung in HA als Custom Component (`fan`-Entity mit Preset-Modes für die
   Stufen, plus Boost als `switch` und Feuchte/Temperatur als `sensor`).

## Offene Punkte

- Zuordnung Zirconia ↔ konkretes inVENTer-Reglermodell am Gerät verifizieren.
- Vollständige Feldbelegung von `ProtocolPacket` (Tail/Prüfsumme) ist im SDK
  vorhanden, hier noch nicht ausgeschrieben.
- Ob der Regler mehrere gleichzeitige TLS-Sessions erlaubt (App + HA parallel)
  ist offen und praktisch zu testen.
