/**
 *  Qubino Flush 1 Relay
 *
 *  Copyright 2016 Tom Philip
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
  definition (name: "Qubino Flush 1 Relay", namespace: "tommysqueak", author: "Tom Philip") {
    capability "Actuator"
    capability "Sensor"
    capability "Switch"
    capability "Relay Switch"
    capability "Temperature Measurement"
    capability "Power Meter"
    capability "Energy Meter"
    capability "Refresh"
    capability "Configuration"

    command "reset"

    fingerprint deviceId: "0x1101", inClusters: "0x5E, 0x86, 0x72, 0x5A, 0x73, 0x20, 0x27, 0x25, 0x26, 0x32, 0x85, 0x8E, 0x59, 0x70", outClusters: "0x20, 0x26", model: "0051", prod: "0001"
	}

	simulator {
		// TODO: define status and reply messages here
	}

  tiles(scale: 2) {
    multiAttributeTile(name:"switch", type: "generic", width: 6, height: 4, canChangeIcon: true){
      tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
        attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.switch.on", backgroundColor:"#00a0dc"
        attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.switch.off", backgroundColor:"#ffffff"
      }
      tileAttribute("device.temperature", key: "SECONDARY_CONTROL") {
        attributeState "default", label:'${currentValue} °C'
      }
    }
    valueTile("power", "device.power", decoration: "flat", width: 2, height: 2) {
      state "default", label:'${currentValue} W'
    }
    valueTile("energy", "device.energy", decoration: "flat", width: 2, height: 2) {
      state "default", label:'${currentValue} kWh'
    }
    standardTile("reset", "device.energy", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
      state "default", label:'reset kWh', action:"reset"
    }
    standardTile("refresh", "device.power", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
      state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
    }

    main(["switch","power","energy"])
    details(["switch","power","energy","refresh","reset"])
  }

  preferences {
    input "switchType", "enum", title: "Switch Type", options: ["Toggle", "Push Button"], required: true, displayDuringSetup: true, defaultValue: "Toggle"
    input "lastKnownState", "enum", title: "After Power Failure", options: ["Return to Last Known State", "Off"], required: true, displayDuringSetup: true
    input "autoTurnOff", "number", title: "Automatically Turn Off (minutes)", description: "Turn off, if left on after so many minutes", range: "0..542", displayDuringSetup: false
    input "temperatureOffset", "decimal", title: "Temperature Offset (°C)", description: "Adjust temperature by this many degrees °C", range: "-10..10", displayDuringSetup: false, defaultValue: 0
    input "temperatureReportOnChange", "decimal", title: "Temperature Reporting Change (°C)", description: "Reports temperature when the change is by this amount °C", range: "0..12", displayDuringSetup: false, defaultValue: 0.5
  }
}

// parse events into attributes
def parse(String description) {

  def result = null
  def cmd = zwave.parse(description)
  if (cmd) {
    result = zwaveEvent(cmd)
    log.debug "Parsed ${cmd} to ${result.inspect()}"
  } else {
    log.debug "Non-parsed event: ${description}"
  }
  return result
}

//	Called when the device handler changes, or preferences change
def updated() {
  log.debug("updated")
  //	TODO: is this getting called for each updated field!?
  response(configure() + refresh())
}

//	NB I like the way to distingiush between, physical and digital events on the switch
//	The module will send BasicReports when it's physical. But when it's done by code, we ask for BinaryReports, so it comes in as digital.
def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd)
{
  createEvent(name: "switch", value: cmd.value ? "on" : "off", type: "physical")
}

def zwaveEvent(physicalgraph.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd)
{
  createEvent(name: "switch", value: cmd.value ? "on" : "off", type: "digital")
}

def zwaveEvent(physicalgraph.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd)
{
  // 1 = temperature
  if(cmd.sensorType == 1){
    createEvent(name: "temperature", value: cmd.scaledSensorValue, unit: cmd.scale == 1 ? "F" : "C")
  }
  else {
    log.debug("WAT!")
  }
}

def zwaveEvent(physicalgraph.zwave.commands.meterv3.MeterReport cmd) {
  if (cmd.meterType == 1) {
    if (cmd.scale == 0) {
      return createEvent(name: "energy", value: cmd.scaledMeterValue, unit: "kWh")
    } else if (cmd.scale == 1) {
      return createEvent(name: "energy", value: cmd.scaledMeterValue, unit: "kVAh")
    } else if (cmd.scale == 2) {
      return createEvent(name: "power", value: Math.round(cmd.scaledMeterValue), unit: "W")
    } else {
      return createEvent(name: "electric", value: cmd.scaledMeterValue, unit: ["pulses", "V", "A", "R/Z", ""][cmd.scale - 3])
    }
  }
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
  // This will capture any commands not handled by other instances of zwaveEvent
  // and is recommended for development so you can see every command the device sends
  return createEvent(descriptionText: "Uncaptured event for ${device.displayName}: ${cmd}")
}

// ************
// * COMMANDS *
// ************

def on() {
  log.debug("on")
  zwave.basicV1.basicSet(value: 0xFF).format()
}

def off() {
  log.debug("off")
  zwave.basicV1.basicSet(value: 0x00).format()
}

def refresh() {
  log.debug("refresh")

  delayBetween([
    zwave.switchBinaryV1.switchBinaryGet().format(),
    zwave.meterV2.meterGet(scale: 0).format(), // get kWh
    zwave.meterV2.meterGet(scale: 2).format(), // get Watts
    zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x01).format(), //temperature
  ], 1000)
}

def reset() {
  log.debug("reset")

  delayBetween([
    zwave.meterV2.meterReset().format(),
    zwave.meterV2.meterGet(scale: 0).format(),
    zwave.meterV2.meterGet(scale: 2).format()
  ], 2000)
}

def configure() {
  log.debug("configure")

  def switchTypeConfig = switchType == "Push Button" ? 0 : 1
  def temperatureOffsetInCelsius = temperatureOffset ?: 0
  def temperatureReportOnChangeConfig = (temperatureReportOnChange ?: 0) * 10
  def autoTurnOffConfig = (autoTurnOff ?: 0) * 60

  def lastKnownStateConfig
  if(lastKnownState == "Off") {
    lastKnownStateConfig = 1
  }
  else {
    lastKnownStateConfig = 0
  }

  def temperatureOffsetConfig
  if(temperatureOffsetInCelsius > 0) {
    temperatureOffsetConfig = Math.round(temperatureOffsetInCelsius * 10)
  }
  else if(temperatureOffsetInCelsius < 0) {
    temperatureOffsetConfig = 1000 + Math.abs(Math.round(temperatureOffsetInCelsius * 10))
  }
  else {
    //	32536 = 0°C - default.
    temperatureOffsetConfig = 32536
  }

  delayBetween([
    //	Switch type: 0 - mono-stable (push button), 1 - bi-stable
    zwave.configurationV1.configurationSet(parameterNumber: 1, size: 1, scaledConfigurationValue: switchTypeConfig).format(),
    //	Turn off after a set time, in seconds. Useful for something staying on too long eg radiator. Max value - 32535 seconds
    zwave.configurationV1.configurationSet(parameterNumber: 11, size: 2, scaledConfigurationValue: autoTurnOffConfig).format(),
    //	State of switch after a power failure. 0 - back to last known state, 1 - off
    zwave.configurationV1.configurationSet(parameterNumber: 30, size: 1, scaledConfigurationValue: lastKnownStateConfig).format(),
    //	Temperature offset.
    //	32536 - default.
    //	1 to 100 - value from 0.1°C to 10.0°C is added to measured temperature.
    //	1001 to 1100 - value from -0.1 °C to -10.0 °C is subtracted from measured temperature.
    zwave.configurationV1.configurationSet(parameterNumber: 110, size: 2, scaledConfigurationValue: temperatureOffsetConfig).format(),
    //	When to report temperature.
    //	5 (0.5°C) - default
    //	0 - Reporting disabled
    //	1-127 = 0.1°C – 12.7°C, step is 0.1°C
    zwave.configurationV1.configurationSet(parameterNumber: 120, size: 1, scaledConfigurationValue: temperatureReportOnChangeConfig).format(),
  ], 3000)
}

//	temporary
def check() {

  log.debug("check")

  delayBetween([
    zwave.configurationV1.configurationGet(parameterNumber: 1).format(),
    zwave.configurationV1.configurationGet(parameterNumber: 11).format(),
    zwave.configurationV1.configurationGet(parameterNumber: 30).format(),
    zwave.configurationV1.configurationGet(parameterNumber: 110).format(),
    zwave.configurationV1.configurationGet(parameterNumber: 120).format(),
  ], 5000)
}