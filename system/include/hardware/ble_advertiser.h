/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Changes from Qualcomm Innovation Center, Inc. are provided under the following license:
 * Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

#ifndef ANDROID_INCLUDE_BLE_ADVERTISER_H
#define ANDROID_INCLUDE_BLE_ADVERTISER_H

#include <base/functional/callback_forward.h>
#include <raw_address.h>
#include <stdint.h>

#include <vector>

#include "bt_common_types.h"
#include "bt_gatt_types.h"

constexpr uint8_t kAdvertiserClientIdJni = 0xff;
constexpr uint8_t kAdvertiserClientIdLeAudio = 0x1;

struct AdvertiseParameters {
  uint16_t advertising_event_properties;
  uint32_t min_interval;
  uint32_t max_interval;
  uint8_t channel_map;
  int8_t tx_power;
  uint8_t primary_advertising_phy;
  uint8_t secondary_advertising_phy;
  uint8_t scan_request_notification_enable;
  int8_t own_address_type;
};

struct CreateBIGParameters {
  uint8_t adv_handle;
  uint8_t num_bis;
  uint32_t sdu_int;
  uint16_t max_sdu;
  uint16_t max_transport_latency;
  uint8_t rtn;
  uint8_t phy;
  uint8_t packing;
  uint8_t framing;
  uint8_t encryption;
  std::vector<uint8_t> broadcast_code;
};

struct PeriodicAdvertisingParameters {
  bool enable;
  bool include_adi;
  uint16_t min_interval;
  uint16_t max_interval;
  uint16_t periodic_advertising_properties;
};

/**
 * LE Advertising related callbacks invoked from from the Bluetooth native stack
 * All callbacks are invoked on the JNI thread
 */
class AdvertisingCallbacks {
 public:
  virtual ~AdvertisingCallbacks() = default;
  virtual void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id,
                                       int8_t tx_power, uint8_t status) = 0;
  virtual void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                    uint8_t status) = 0;
  virtual void OnAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) = 0;
  virtual void OnScanResponseDataSet(uint8_t advertiser_id, uint8_t status) = 0;
  virtual void OnAdvertisingParametersUpdated(uint8_t advertiser_id,
                                              int8_t tx_power,
                                              uint8_t status) = 0;
  virtual void OnPeriodicAdvertisingParametersUpdated(uint8_t advertiser_id,
                                                      uint8_t status) = 0;
  virtual void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id,
                                            uint8_t status) = 0;
  virtual void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                            uint8_t status) = 0;
  virtual void OnOwnAddressRead(uint8_t advertiser_id, uint8_t address_type,
                                RawAddress address) = 0;
};

class BleAdvertiserInterface {
 public:
  virtual ~BleAdvertiserInterface() = default;

  /** Callback invoked when multi-adv operation has completed */
  using StatusCallback = base::Callback<void(uint8_t /* status */)>;
  using IdStatusCallback =
      base::Callback<void(uint8_t /* advertiser_id */, uint8_t /* status */)>;
  using IdTxPowerStatusCallback =
      base::Callback<void(uint8_t /* advertiser_id */, int8_t /* tx_power */,
                          uint8_t /* status */)>;
  using ParametersCallback =
      base::Callback<void(uint8_t /* status */, int8_t /* tx_power */)>;
  using CreateBIGCallback = base::Callback<void(uint8_t /*adv_inst_id*/,
      uint8_t /*status*/, uint8_t /*big_handle*/, uint32_t /*big_sync_delay*/,
      uint32_t /*transport_latency_big*/, uint8_t /*phy*/, uint8_t /*nse*/,
      uint8_t /*bn*/, uint8_t /*pto*/, uint8_t /*irc*/, uint16_t /*max_pdu*/,
      uint16_t /*iso_int*/, uint8_t /*num_bis*/,
      std::vector<uint16_t> /*conn_handle_list*/)>;
  using TerminateBIGCallback =
      base::Callback<void(uint8_t /* status */, uint8_t /* advertiser_id */,
      uint8_t /* big_handle */, uint8_t /* reason */)>;
  /** Registers an advertiser with the stack */
  virtual void RegisterAdvertiser(IdStatusCallback) = 0;

  using GetAddressCallback =
      base::Callback<void(uint8_t /* address_type*/, RawAddress /*address*/)>;
  virtual void GetOwnAddress(uint8_t advertiser_id, GetAddressCallback cb) = 0;

  /* Set the parameters as per spec, user manual specified values */
  virtual void SetParameters(uint8_t advertiser_id, AdvertiseParameters params,
                             ParametersCallback cb) = 0;

  /* Setup the data */
  virtual void SetData(int advertiser_id, bool set_scan_rsp,
                       std::vector<uint8_t> data, std::vector<uint8_t> data_enc,
                       StatusCallback cb) = 0;

  /* Enable the advertising instance */
  virtual void Enable(uint8_t advertiser_id, bool enable, StatusCallback cb,
                      uint16_t duration, uint8_t maxExtAdvEvents,
                      StatusCallback timeout_cb) = 0;

  /*  Unregisters an advertiser */
  virtual void Unregister(uint8_t advertiser_id) = 0;

  virtual void StartAdvertising(uint8_t advertiser_id, StatusCallback cb,
                                AdvertiseParameters params,
                                std::vector<uint8_t> advertise_data,
                                std::vector<uint8_t> scan_response_data,
                                int timeout_s, StatusCallback timeout_cb) = 0;

  /** Start the advertising set. This include registering, setting all
   * parameters and data, and enabling it. |register_cb| is called when the set
   * is advertising. |timeout_cb| is called when the timeout_s have passed.
   * |reg_id| is the callback id assigned from upper or native layer.
   * |client_id| is the callbacks client id for jni or native layer.
   */
  virtual void StartAdvertisingSet(
      uint8_t client_id, int reg_id, IdTxPowerStatusCallback register_cb,
      AdvertiseParameters params, std::vector<uint8_t> advertise_data,
      std::vector<uint8_t> advertise_data_enc,
      std::vector<uint8_t> scan_response_data,
      std::vector<uint8_t> scan_response_data_enc,
      PeriodicAdvertisingParameters periodic_params,
      std::vector<uint8_t> periodic_data,
      std::vector<uint8_t> periodic_data_enc, uint16_t duration,
      uint8_t maxExtAdvEvents, std::vector<uint8_t> enc_key_value,
      IdStatusCallback timeout_cb) = 0;

  virtual void SetPeriodicAdvertisingParameters(
      int advertiser_id, PeriodicAdvertisingParameters parameters,
      StatusCallback cb) = 0;

  virtual void SetPeriodicAdvertisingData(int advertiser_id,
                                          std::vector<uint8_t> data,
                                          std::vector<uint8_t> data_enc,
                                          StatusCallback cb) = 0;

  virtual void CreateBIG(
      int advertiser_id, CreateBIGParameters create_big_params,
      CreateBIGCallback cb) = 0;

  virtual void TerminateBIG(
      int advertiser_id, int big_handle, int reason,
      TerminateBIGCallback cb) = 0;

  virtual void SetPeriodicAdvertisingEnable(int advertiser_id, bool enable,
                                            bool include_adi,
                                            StatusCallback cb) = 0;
  virtual void RegisterCallbacks(AdvertisingCallbacks* callbacks) = 0;
  virtual void RegisterCallbacksNative(AdvertisingCallbacks* callbacks,
                                       uint8_t client_id) = 0;
};

#endif /* ANDROID_INCLUDE_BLE_ADVERTISER_H */
