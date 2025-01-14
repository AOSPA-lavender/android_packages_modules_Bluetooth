/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "bt_shim_advertiser"

#include "le_advertising_manager.h"

#include <base/logging.h>
#include <base/strings/string_number_conversions.h>
#include <bluetooth/log.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_gatt.h>
#include <com_android_bluetooth_flags.h>

#include <vector>

#include "btif/include/btif_common.h"
#include "common/init_flags.h"
#include "hci/le_advertising_manager.h"
#include "main/shim/entry.h"
#include "main/shim/helpers.h"
#include "stack/include/btm_log_history.h"
#include "stack/include/gap_api.h"
#include "stack/include/main_thread.h"
#include "types/raw_address.h"
#include "utils.h"

using bluetooth::hci::Address;
using bluetooth::hci::AddressType;
using bluetooth::hci::AdvertiserAddressType;
using bluetooth::hci::ErrorCode;
using bluetooth::hci::GapData;
using bluetooth::hci::OwnAddressType;
using bluetooth::shim::parse_gap_data;
using std::vector;
using namespace bluetooth;

namespace bluetooth {
namespace shim {

class EncKeyMaterialInterfaceImpl
    : public EncKeyMaterialInterface,
      public bluetooth::hci::EncKeyMaterialCallback {
 public:
  ~EncKeyMaterialInterfaceImpl() override{};
  void GetEncKeyMaterial() {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->GetEncKeyMaterial();
  }

  void Init() {
    bluetooth::shim::GetAdvertising()->RegisterEncKeyMaterialCallback(this);
  }

  void OnGetEncKeyMaterial(std::vector<uint8_t> temp, uint16_t attr_uuid) {
    tGAP_BLE_ATTR_VALUE* temp_attr = new tGAP_BLE_ATTR_VALUE;
    std::copy(temp.begin(), temp.begin() + 16,
              temp_attr->enc_key_material.session_key);
    std::copy(temp.begin() + 16, temp.end(),
              temp_attr->enc_key_material.init_vector);
    GAP_BleAttrDBUpdate(attr_uuid, temp_attr);
  }
};
}  // namespace shim
}  // namespace bluetooth

namespace {
constexpr char kBtmLogTag[] = "ADV";
}

class BleAdvertiserInterfaceImpl : public BleAdvertiserInterface,
                                   public bluetooth::hci::AdvertisingCallback {
 public:
  ~BleAdvertiserInterfaceImpl() override{};
  const bool kEncryptedAdvertisingDataSupported = true;

  void Init() {
    // Register callback
    bluetooth::shim::GetAdvertising()->RegisterAdvertisingCallback(this);
  }

  void RegisterAdvertiser(IdStatusCallback cb) override {
    log::info("in shim layer");

    bluetooth::shim::GetAdvertising()->RegisterAdvertiser(
        bluetooth::shim::GetGdShimHandler()->BindOnce(
            [](IdStatusCallback cb, uint8_t id, uint8_t status) {
              do_in_main_thread(
                  FROM_HERE,
                  base::BindOnce([](IdStatusCallback cb, uint8_t id,
                                    uint8_t status) { cb.Run(id, status); },
                                 cb, id, status));
            },
            cb));
  }

  void Unregister(uint8_t advertiser_id) override {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->RemoveAdvertiser(advertiser_id);
    int reg_id =
        bluetooth::shim::GetAdvertising()->GetAdvertiserRegId(advertiser_id);
    uint8_t client_id = is_native_advertiser(reg_id);
    // if registered by native client, remove the register id
    if (client_id != kAdvertiserClientIdJni) {
      native_reg_id_map[client_id].erase(reg_id);
    }
    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le advert stopped",
                   base::StringPrintf("advert_id:%d", advertiser_id));
  }

  void GetOwnAddress(uint8_t advertiser_id, GetAddressCallback cb) override {
    log::info("in shim layer");
    address_callbacks_[advertiser_id] = jni_thread_wrapper(cb);
    bluetooth::shim::GetAdvertising()->GetOwnAddress(advertiser_id);
  }

  void SetParameters(uint8_t advertiser_id, AdvertiseParameters params,
                     ParametersCallback /* cb */) override {
    log::info("in shim layer");
    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);
    bluetooth::shim::GetAdvertising()->SetParameters(advertiser_id, config);
  }

  void SetData(int advertiser_id, bool set_scan_rsp, vector<uint8_t> data,
               vector<uint8_t> data_encrypt, StatusCallback cb) override {
    log::info("in shim layer");
    std::vector<GapData> advertising_data = {};
    parse_gap_data(data, advertising_data);

    if (!kEncryptedAdvertisingDataSupported) {
      bluetooth::shim::GetAdvertising()->SetData(advertiser_id, set_scan_rsp,
                                                 advertising_data);
    } else {
      std::vector<GapData> advertising_data_encrypt = {};
      parse_gap_data(data_encrypt, advertising_data_encrypt);
      bluetooth::shim::GetAdvertising()->SetData(advertiser_id, set_scan_rsp,
                                                 advertising_data,
                                                 advertising_data_encrypt);
    }
  }

  void Enable(uint8_t advertiser_id, bool enable, StatusCallback /* cb */,
              uint16_t duration, uint8_t maxExtAdvEvents,
              StatusCallback /* timeout_cb */) override {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->EnableAdvertiser(
        advertiser_id, enable, duration, maxExtAdvEvents);
  }

  // nobody use this function
  void StartAdvertising(uint8_t advertiser_id, StatusCallback cb,
                        AdvertiseParameters params,
                        std::vector<uint8_t> advertise_data,
                        std::vector<uint8_t> scan_response_data, int timeout_s,
                        StatusCallback timeout_cb) override {
    log::info("in shim layer");

    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);

    parse_gap_data(advertise_data, config.advertisement);
    parse_gap_data(scan_response_data, config.scan_response);

    bluetooth::shim::GetAdvertising()->StartAdvertising(
        advertiser_id, config, timeout_s * 100, cb, timeout_cb, scan_callback,
        set_terminated_callback, bluetooth::shim::GetGdShimHandler());
  }

  void StartAdvertisingSet(uint8_t client_id, int reg_id,
                           IdTxPowerStatusCallback /* register_cb */,
                           AdvertiseParameters params,
                           std::vector<uint8_t> advertise_data,
                           std::vector<uint8_t> advertise_data_enc,
                           std::vector<uint8_t> scan_response_data,
                           std::vector<uint8_t> scan_response_data_enc,
                           PeriodicAdvertisingParameters periodic_params,
                           std::vector<uint8_t> periodic_data,
                           std::vector<uint8_t> periodic_data_enc,
                           uint16_t duration, uint8_t maxExtAdvEvents,
                           std::vector<uint8_t> enc_key_value,
                           IdStatusCallback timeout_cb) {
    log::info("in shim layer");

    bluetooth::hci::AdvertisingConfig config{};
    parse_parameter(config, params);
    parse_periodic_advertising_parameter(config.periodic_advertising_parameters,
                                         periodic_params);

    parse_gap_data(advertise_data, config.advertisement);
    parse_gap_data(scan_response_data, config.scan_response);
    parse_gap_data(periodic_data, config.periodic_data);

    if (kEncryptedAdvertisingDataSupported) {
      config.enc_key_value = enc_key_value;
      parse_gap_data(advertise_data_enc, config.advertisement_enc);
      parse_gap_data(scan_response_data_enc, config.scan_response_enc);
      parse_gap_data(periodic_data_enc, config.periodic_data_enc);
    }

    // if registered by native client, add the register id
    if (client_id != kAdvertiserClientIdJni) {
      native_reg_id_map[client_id].insert(reg_id);
    }

    bluetooth::shim::GetAdvertising()->ExtendedCreateAdvertiser(
        client_id, reg_id, config, scan_callback, set_terminated_callback,
        duration, maxExtAdvEvents, bluetooth::shim::GetGdShimHandler());

    log::info("create advertising set, client_id:{}, reg_id:{}", client_id,
              reg_id);
    BTM_LogHistory(kBtmLogTag, RawAddress::kEmpty, "Le advert started",
                   base::StringPrintf("reg_id:%d", reg_id));

    return;
  }

  void SetPeriodicAdvertisingParameters(
      int advertiser_id, PeriodicAdvertisingParameters periodic_params,
      StatusCallback /* cb */) override {
    log::info("in shim layer");
    bluetooth::hci::PeriodicAdvertisingParameters parameters;
    parameters.max_interval = periodic_params.max_interval;
    parameters.min_interval = periodic_params.min_interval;
    parameters.properties = periodic_params.periodic_advertising_properties;
    bluetooth::shim::GetAdvertising()->SetPeriodicParameters(advertiser_id,
                                                             parameters);
  }

  void SetPeriodicAdvertisingData(int advertiser_id, std::vector<uint8_t> data,
                                  std::vector<uint8_t> data_encrypt,
                                  StatusCallback /* cb */) override {
    log::info("in shim layer");
    std::vector<GapData> advertising_data = {};
    parse_gap_data(data, advertising_data);

    if (!true) {
      bluetooth::shim::GetAdvertising()->SetPeriodicData(advertiser_id,
                                                         advertising_data);
    } else {
      std::vector<GapData> advertising_data_encrypt = {};
      parse_gap_data(data_encrypt, advertising_data_encrypt);
      bluetooth::shim::GetAdvertising()->SetPeriodicData(
          advertiser_id, advertising_data, advertising_data_encrypt);
    }
  }

  void SetPeriodicAdvertisingEnable(int advertiser_id, bool enable,
                                    bool include_adi,
                                    StatusCallback /* cb */) override {
    log::info("in shim layer");
    bluetooth::shim::GetAdvertising()->EnablePeriodicAdvertising(
        advertiser_id, enable, include_adi);
  }

  void RegisterCallbacks(AdvertisingCallbacks* callbacks) {
    advertising_callbacks_ = callbacks;
  }

  void RegisterCallbacksNative(AdvertisingCallbacks* callbacks,
                               uint8_t client_id) {
    native_adv_callbacks_map_[client_id] = callbacks;
  }

  void on_scan(Address /* address */, AddressType /* address_type */) {
    log::info("in shim layer");
  }

  void on_set_terminated(ErrorCode /* error_code */, uint8_t, uint8_t) {
    log::info("in shim layer");
  }

  const bluetooth::common::Callback<void(Address, AddressType)> scan_callback =
      bluetooth::common::Bind(&BleAdvertiserInterfaceImpl::on_scan,
                              bluetooth::common::Unretained(this));

  const bluetooth::common::Callback<void(ErrorCode, uint8_t, uint8_t)>
      set_terminated_callback = bluetooth::common::Bind(
          &BleAdvertiserInterfaceImpl::on_set_terminated,
          bluetooth::common::Unretained(this));

  // AdvertisingCallback
  void OnAdvertisingSetStarted(int reg_id, uint8_t advertiser_id,
                               int8_t tx_power,
                               AdvertisingStatus status) override {
    uint8_t client_id = is_native_advertiser(reg_id);

    if (status != AdvertisingCallback::AdvertisingStatus::SUCCESS) {
       log::info("Status is invalid, reset advertiser id: {}", advertiser_id);
       bluetooth::shim::GetAdvertising()->ResetAdvertiser(advertiser_id);
    }
    if (client_id != kAdvertiserClientIdJni) {
      // Invoke callback for native client
      do_in_main_thread(
          FROM_HERE,
          base::Bind(&AdvertisingCallbacks::OnAdvertisingSetStarted,
                     base::Unretained(native_adv_callbacks_map_[client_id]),
                     reg_id, advertiser_id, tx_power, status));
      return;
    }
    do_in_jni_thread(
        base::BindOnce(&AdvertisingCallbacks::OnAdvertisingSetStarted,
                       base::Unretained(advertising_callbacks_), reg_id,
                       advertiser_id, tx_power, status));
  }

  void OnAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                            uint8_t status) {
    int reg_id =
        bluetooth::shim::GetAdvertising()->GetAdvertiserRegId(advertiser_id);
    uint8_t client_id = is_native_advertiser(reg_id);
    if (client_id != kAdvertiserClientIdJni) {
      // Invoke callback for native client
      do_in_main_thread(
          FROM_HERE,
          base::Bind(&AdvertisingCallbacks::OnAdvertisingEnabled,
                     base::Unretained(native_adv_callbacks_map_[client_id]),
                     advertiser_id, enable, status));
      return;
    }
    do_in_jni_thread(base::BindOnce(&AdvertisingCallbacks::OnAdvertisingEnabled,
                                    base::Unretained(advertising_callbacks_),
                                    advertiser_id, enable, status));
  }

  void OnAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
    do_in_jni_thread(base::BindOnce(&AdvertisingCallbacks::OnAdvertisingDataSet,
                                    base::Unretained(advertising_callbacks_),
                                    advertiser_id, status));
  }
  void OnScanResponseDataSet(uint8_t advertiser_id, uint8_t status) {
    do_in_jni_thread(base::BindOnce(
        &AdvertisingCallbacks::OnScanResponseDataSet,
        base::Unretained(advertising_callbacks_), advertiser_id, status));
  }

  void OnAdvertisingParametersUpdated(uint8_t advertiser_id, int8_t tx_power,
                                      uint8_t status) {
    do_in_jni_thread(
        base::BindOnce(&AdvertisingCallbacks::OnAdvertisingParametersUpdated,
                       base::Unretained(advertising_callbacks_), advertiser_id,
                       tx_power, status));
  }

  void OnPeriodicAdvertisingParametersUpdated(uint8_t advertiser_id,
                                              uint8_t status) {
    do_in_jni_thread(base::BindOnce(
        &AdvertisingCallbacks::OnPeriodicAdvertisingParametersUpdated,
        base::Unretained(advertising_callbacks_), advertiser_id, status));
  }

  void OnPeriodicAdvertisingDataSet(uint8_t advertiser_id, uint8_t status) {
    do_in_jni_thread(base::BindOnce(
        &AdvertisingCallbacks::OnPeriodicAdvertisingDataSet,
        base::Unretained(advertising_callbacks_), advertiser_id, status));
  }

  void OnPeriodicAdvertisingEnabled(uint8_t advertiser_id, bool enable,
                                    uint8_t status) {
    do_in_jni_thread(
        base::BindOnce(&AdvertisingCallbacks::OnPeriodicAdvertisingEnabled,
                       base::Unretained(advertising_callbacks_), advertiser_id,
                       enable, status));
  }

  void OnOwnAddressRead(uint8_t advertiser_id, uint8_t address_type,
                        bluetooth::hci::Address address) {
    RawAddress raw_address = bluetooth::ToRawAddress(address);
    if (address_callbacks_.find(advertiser_id) != address_callbacks_.end()) {
      address_callbacks_[advertiser_id].Run(address_type, raw_address);
      address_callbacks_.erase(advertiser_id);
      return;
    }
    do_in_jni_thread(base::BindOnce(&AdvertisingCallbacks::OnOwnAddressRead,
                                    base::Unretained(advertising_callbacks_),
                                    advertiser_id, address_type, raw_address));
  }

  void CreateBIG(int advertiser_id, CreateBIGParameters create_big_params,
                 CreateBIGCallback cb) {}

  void TerminateBIG(int advertiser_id, int big_handle, int reason,
                    TerminateBIGCallback cb) {}

  AdvertisingCallbacks* advertising_callbacks_;
  std::map<uint8_t, AdvertisingCallbacks*> native_adv_callbacks_map_;

 private:
  void parse_parameter(bluetooth::hci::AdvertisingConfig& config,
                       AdvertiseParameters params) {
    config.connectable = params.advertising_event_properties & 0x01;
    config.scannable = params.advertising_event_properties & 0x02;
    config.discoverable = params.advertising_event_properties & 0x04;
    config.legacy_pdus = params.advertising_event_properties & 0x10;
    config.anonymous = params.advertising_event_properties & 0x20;
    config.include_tx_power = params.advertising_event_properties & 0x40;
    config.interval_min = params.min_interval;
    config.interval_max = params.max_interval;
    config.channel_map = params.channel_map;
    config.tx_power = params.tx_power;
    config.use_le_coded_phy = params.primary_advertising_phy == 0x03;
    config.secondary_advertising_phy =
        static_cast<bluetooth::hci::SecondaryPhyType>(
            params.secondary_advertising_phy);
    config.enable_scan_request_notifications =
        static_cast<bluetooth::hci::Enable>(
            params.scan_request_notification_enable);
    // Matching the ADDRESS_TYPE_* enums from Java
    switch (params.own_address_type) {
      case -1:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::RESOLVABLE_RANDOM;
        break;
      case 0:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::PUBLIC;
        break;
      case 1:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::RESOLVABLE_RANDOM;
        break;
      case 2:
        config.requested_advertiser_address_type =
            AdvertiserAddressType::NONRESOLVABLE_RANDOM;
        break;
      default:
        log::error("Received unexpected address type: {}",
                   params.own_address_type);
        config.requested_advertiser_address_type =
            AdvertiserAddressType::RESOLVABLE_RANDOM;
    }
  }

  void parse_periodic_advertising_parameter(
      bluetooth::hci::PeriodicAdvertisingParameters& config,
      PeriodicAdvertisingParameters periodic_params) {
    config.max_interval = periodic_params.max_interval;
    config.min_interval = periodic_params.min_interval;
    config.properties = periodic_params.periodic_advertising_properties;
    config.enable = periodic_params.enable;
    config.include_adi = periodic_params.include_adi;
  }

  uint8_t is_native_advertiser(int reg_id) {
    // Return client id if it's native advertiser, otherwise return jni id as
    // default
    for (auto const& entry : native_adv_callbacks_map_) {
      if (native_reg_id_map[entry.first].count(reg_id)) {
        return entry.first;
      }
    }
    return kAdvertiserClientIdJni;
  }

  std::map<uint8_t, GetAddressCallback> address_callbacks_;
  std::map<uint8_t, std::set<int>> native_reg_id_map;
};

BleAdvertiserInterfaceImpl* bt_le_advertiser_instance = nullptr;

BleAdvertiserInterface* bluetooth::shim::get_ble_advertiser_instance() {
  if (bt_le_advertiser_instance == nullptr) {
    bt_le_advertiser_instance = new BleAdvertiserInterfaceImpl();
  }
  return bt_le_advertiser_instance;
};

void bluetooth::shim::init_advertising_manager() {
  static_cast<BleAdvertiserInterfaceImpl*>(
      bluetooth::shim::get_ble_advertiser_instance())
      ->Init();
}

bluetooth::shim::EncKeyMaterialInterface* enc_key_material_instance = nullptr;

bluetooth::shim::EncKeyMaterialInterface*
bluetooth::shim::get_enc_key_material_instance() {
  if (enc_key_material_instance == nullptr) {
    enc_key_material_instance =
        new bluetooth::shim::EncKeyMaterialInterfaceImpl();
  }
  return enc_key_material_instance;
}
void bluetooth::shim::init_enc_key_material_manager() {
  static_cast<bluetooth::shim::EncKeyMaterialInterface*>(
      bluetooth::shim::get_enc_key_material_instance())
      ->Init();
}
