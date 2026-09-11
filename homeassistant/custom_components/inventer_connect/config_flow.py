"""Config flow for inVENTer Connect."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol
from homeassistant.components.bluetooth import (
    BluetoothServiceInfoBleak,
    async_ble_device_from_address,
    async_discovered_service_info,
)
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_ADDRESS
from homeassistant.core import callback
from homeassistant.helpers import config_validation as cv

from .client import InventerAuthError, InventerError, async_probe
from .const import (
    CONF_KEEP_CONNECTED,
    CONF_PIN,
    CONF_ZONE,
    CONNECTION_SERVICE,
    DEFAULT_ZONE,
    DOMAIN,
    TRANSPORT_CHARACTERISTIC,
)

_LOGGER = logging.getLogger(__name__)

PIN_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_PIN): cv.positive_int,
        vol.Optional(CONF_ZONE, default=DEFAULT_ZONE): vol.All(int, vol.Range(min=0, max=15)),
    }
)


class InventerConfigFlow(ConfigFlow, domain=DOMAIN):
    """Pick a controller, then verify the PIN printed in its manual."""

    VERSION = 1

    def __init__(self) -> None:
        self._discovered_address: str | None = None
        self._discovered_name: str | None = None

    async def async_step_bluetooth(
        self, discovery_info: BluetoothServiceInfoBleak
    ) -> ConfigFlowResult:
        """Handle a controller found by the Bluetooth integration."""
        await self.async_set_unique_id(discovery_info.address)
        self._abort_if_unique_id_configured()

        self._discovered_address = discovery_info.address
        self._discovered_name = discovery_info.name or discovery_info.address
        self.context["title_placeholders"] = {"name": self._discovered_name}
        return await self.async_step_pin()

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Let the user choose among nearby controllers."""
        if user_input is not None:
            self._discovered_address = user_input[CONF_ADDRESS]
            await self.async_set_unique_id(self._discovered_address, raise_on_progress=False)
            self._abort_if_unique_id_configured()
            return await self.async_step_pin()

        candidates = {
            info.address: f"{info.name or 'inVENTer'} ({info.address})"
            for info in async_discovered_service_info(self.hass, connectable=True)
            if self._looks_like_controller(info)
        }
        for entry in self._async_current_entries():
            candidates.pop(entry.data.get(CONF_ADDRESS), None)

        if not candidates:
            return self.async_abort(reason="no_devices_found")

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema({vol.Required(CONF_ADDRESS): vol.In(candidates)}),
        )

    async def async_step_pin(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Verify the PIN by connecting once."""
        errors: dict[str, str] = {}

        if user_input is not None:
            assert self._discovered_address is not None
            device = async_ble_device_from_address(
                self.hass, self._discovered_address, connectable=True)

            if device is None:
                errors["base"] = "cannot_connect"
            else:
                try:
                    info = await async_probe(device, user_input[CONF_PIN])
                except InventerAuthError:
                    errors[CONF_PIN] = "invalid_pin"
                except InventerError as err:
                    _LOGGER.debug("Probe failed: %s", err)
                    errors["base"] = "cannot_connect"
                else:
                    name = info.get("model") or self._discovered_name or "inVENTer Connect"
                    return self.async_create_entry(
                        title=name,
                        data={
                            CONF_ADDRESS: self._discovered_address,
                            CONF_PIN: user_input[CONF_PIN],
                            CONF_ZONE: user_input.get(CONF_ZONE, DEFAULT_ZONE),
                        },
                    )

        return self.async_show_form(
            step_id="pin",
            data_schema=PIN_SCHEMA,
            errors=errors,
            description_placeholders={"name": self._discovered_name or ""},
        )

    async def async_step_reauth(self, entry_data: dict[str, Any]) -> ConfigFlowResult:
        """Re-ask for the PIN after the controller rejected it."""
        self._discovered_address = entry_data[CONF_ADDRESS]
        return await self.async_step_pin()

    @staticmethod
    def _looks_like_controller(info: BluetoothServiceInfoBleak) -> bool:
        """Match on the advertised connection service.

        Advertised names vary by model, so the service UUID is the more
        reliable signal. The PIN step confirms compatibility for real by
        checking for the transport characteristic once connected.
        """
        uuids = {uuid.lower() for uuid in info.service_uuids}
        return bool(uuids & {TRANSPORT_CHARACTERISTIC, CONNECTION_SERVICE})

    @staticmethod
    @callback
    def async_get_options_flow(entry: ConfigEntry) -> OptionsFlow:
        return InventerOptionsFlow()


class InventerOptionsFlow(OptionsFlow):
    """Expose the one setting worth changing after setup."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        current = self.config_entry.options.get(CONF_KEEP_CONNECTED, False)
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {vol.Optional(CONF_KEEP_CONNECTED, default=current): bool}
            ),
        )
