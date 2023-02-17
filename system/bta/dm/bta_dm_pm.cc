/******************************************************************************
 *
 *  Copyright 2003-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  This file contains the action functions for device manager state
 *  machine.
 *
 ******************************************************************************/

#include <base/functional/bind.h>

#include <cstdint>
#include <mutex>
#include <vector>

#include "bta/dm/bta_dm_int.h"
#include "bta/include/bta_api.h"
#include "bta/include/bta_dm_api.h"
#include "bta/sys/bta_sys.h"
#include "btif/include/core_callbacks.h"
#include "btif/include/stack_manager.h"
#include "device/include/controller.h"
#include "main/shim/dumpsys.h"
#include "osi/include/log.h"
#include "osi/include/properties.h"
#include "stack/include/acl_api.h"
#include "stack/include/btu.h"  // do_in_main_thread
#include "types/raw_address.h"

static void bta_dm_pm_cback(tBTA_SYS_CONN_STATUS status, uint8_t id,
                            uint8_t app_id, const RawAddress& peer_addr);
static void bta_dm_pm_set_mode(const RawAddress& peer_addr,
                               tBTA_DM_PM_ACTION pm_mode,
                               tBTA_DM_PM_REQ pm_req);
static void bta_dm_pm_timer_cback(void* data);
static void bta_dm_pm_btm_cback(const RawAddress& bd_addr,
                                tBTM_PM_STATUS status, uint16_t value,
                                tHCI_STATUS hci_status);
static bool bta_dm_pm_park(const RawAddress& peer_addr);
void bta_dm_pm_sniff(tBTA_DM_PEER_DEVICE* p_peer_dev, uint8_t index);
static int bta_dm_get_sco_index();
static void bta_dm_pm_stop_timer_by_index(tBTA_PM_TIMER* p_timer,
                                          uint8_t timer_idx);

#include "../hh/bta_hh_int.h"
/* BTA_DM_PM_SSR1 will be dedicated for HH SSR setting entry, no other profile
 * can use it */
#define BTA_DM_PM_SSR_HH BTA_DM_PM_SSR1
static void bta_dm_pm_ssr(const RawAddress& peer_addr, const int ssr);

tBTA_DM_CONNECTED_SRVCS bta_dm_conn_srvcs;
static std::recursive_mutex pm_timer_schedule_mutex;
static std::recursive_mutex pm_timer_state_mutex;

/* Sysprop paths for sniff parameters */
static const char kPropertySniffMaxIntervals[] =
    "bluetooth.core.classic.sniff_max_intervals";
static const char kPropertySniffMinIntervals[] =
    "bluetooth.core.classic.sniff_min_intervals";
static const char kPropertySniffAttempts[] =
    "bluetooth.core.classic.sniff_attempts";
static const char kPropertySniffTimeouts[] =
    "bluetooth.core.classic.sniff_timeouts";

/*******************************************************************************
 *
 * Function         bta_dm_init_pm
 *
 * Description      Initializes the BT low power manager
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_init_pm(void) {
  memset(&bta_dm_conn_srvcs, 0x00, sizeof(bta_dm_conn_srvcs));

  /* if there are no power manger entries, so not register */
  if (p_bta_dm_pm_cfg[0].app_id != 0) {
    bta_sys_pm_register(bta_dm_pm_cback);

    BTM_PmRegister((BTM_PM_REG_SET), &bta_dm_cb.pm_id, bta_dm_pm_btm_cback);
  }

  /* Need to initialize all PM timer service IDs */
  for (int i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
    for (int j = 0; j < BTA_DM_PM_MODE_TIMER_MAX; j++)
      bta_dm_cb.pm_timer[i].srvc_id[j] = BTA_ID_MAX;
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_disable_pm
 *
 * Description      Disable PM
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_disable_pm(void) {
  BTM_PmRegister(BTM_PM_DEREG, &bta_dm_cb.pm_id, bta_dm_pm_btm_cback);

  /*
   * Deregister the PM callback from the system handling to prevent
   * re-enabling the PM timers after this call if the callback is invoked.
   */
  bta_sys_pm_register(NULL);

  /* Need to stop all active timers. */
  for (int i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
    for (int j = 0; j < BTA_DM_PM_MODE_TIMER_MAX; j++) {
      bta_dm_pm_stop_timer_by_index(&bta_dm_cb.pm_timer[i], j);
      bta_dm_cb.pm_timer[i].pm_action[j] = BTA_DM_PM_NO_ACTION;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_get_av_count
 *
 * Description      Get the number of connected AV
 *
 *
 * Returns          number of av connections
 *
 ******************************************************************************/
uint8_t bta_dm_get_av_count(void) {
  uint8_t count = 0;
  for (int i = 0; i < bta_dm_conn_srvcs.count; i++) {
    if (bta_dm_conn_srvcs.conn_srvc[i].id == BTA_ID_AV) ++count;
  }
  return count;
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_stop_timer
 *
 * Description      stop a PM timer
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_stop_timer(const RawAddress& peer_addr) {
  APPL_TRACE_DEBUG("%s: ", __func__);

  for (int i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
    if (bta_dm_cb.pm_timer[i].in_use &&
        bta_dm_cb.pm_timer[i].peer_bdaddr == peer_addr) {
      for (int j = 0; j < BTA_DM_PM_MODE_TIMER_MAX; j++) {
        bta_dm_pm_stop_timer_by_index(&bta_dm_cb.pm_timer[i], j);
        /*
         * TODO: For now, stopping the timer does not reset
         * pm_action[j].
         * The reason is because some of the internal logic that
         * (re)assigns the pm_action[] values is taking into account
         * the older value; e.g., see the pm_action[] assignment in
         * function bta_dm_pm_start_timer().
         * Such subtlety in the execution logic is error prone, and
         * should be eliminiated in the future.
         */
      }
      break;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_pm_action_to_timer_idx
 *
 * Description      convert power mode into timer index for each connected
 *                  device
 *
 *
 * Returns          index of the power mode delay timer
 *
 ******************************************************************************/
static uint8_t bta_pm_action_to_timer_idx(uint8_t pm_action) {
  if (pm_action == BTA_DM_PM_SUSPEND)
    return BTA_DM_PM_SUSPEND_TIMER_IDX;
  else if (pm_action == BTA_DM_PM_PARK)
    return BTA_DM_PM_PARK_TIMER_IDX;
  else if ((pm_action & BTA_DM_PM_SNIFF) == BTA_DM_PM_SNIFF)
    return BTA_DM_PM_SNIFF_TIMER_IDX;

  /* Active, no preference, no action and retry */
  return BTA_DM_PM_MODE_TIMER_MAX;
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_stop_timer_by_mode
 *
 * Description      stop a PM timer
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_stop_timer_by_mode(const RawAddress& peer_addr,
                                         uint8_t power_mode) {
  const uint8_t timer_idx = bta_pm_action_to_timer_idx(power_mode);
  if (timer_idx == BTA_DM_PM_MODE_TIMER_MAX) return;

  for (int i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
    if (bta_dm_cb.pm_timer[i].in_use &&
        bta_dm_cb.pm_timer[i].peer_bdaddr == peer_addr) {
      if (bta_dm_cb.pm_timer[i].srvc_id[timer_idx] != BTA_ID_MAX) {
        bta_dm_pm_stop_timer_by_index(&bta_dm_cb.pm_timer[i], timer_idx);
        /*
         * TODO: Intentionally setting pm_action[timer_idx].
         * This assignment should be eliminated in the future - see the
         * pm_action[] related comment inside function
         * bta_dm_pm_stop_timer().
         */
        bta_dm_cb.pm_timer[i].pm_action[timer_idx] = power_mode;
      }
      break;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_stop_timer_by_srvc_id
 *
 * Description      stop all timer started by the service ID.
 *
 *
 * Returns          index of the power mode delay timer
 *
 ******************************************************************************/
static void bta_dm_pm_stop_timer_by_srvc_id(const RawAddress& peer_addr,
                                            uint8_t srvc_id) {
  for (int i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
    if (bta_dm_cb.pm_timer[i].in_use &&
        bta_dm_cb.pm_timer[i].peer_bdaddr == peer_addr) {
      for (int j = 0; j < BTA_DM_PM_MODE_TIMER_MAX; j++) {
        if (bta_dm_cb.pm_timer[i].srvc_id[j] == srvc_id) {
          bta_dm_pm_stop_timer_by_index(&bta_dm_cb.pm_timer[i], j);
          bta_dm_cb.pm_timer[i].pm_action[j] = BTA_DM_PM_NO_ACTION;
          break;
        }
      }
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_start_timer
 *
 * Description      start a PM timer
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_start_timer(tBTA_PM_TIMER* p_timer, uint8_t timer_idx,
                                  uint64_t timeout_ms, uint8_t srvc_id,
                                  uint8_t pm_action) {
  std::unique_lock<std::recursive_mutex> schedule_lock(pm_timer_schedule_mutex);
  std::unique_lock<std::recursive_mutex> state_lock(pm_timer_state_mutex);
  p_timer->in_use = true;

  if (p_timer->srvc_id[timer_idx] == BTA_ID_MAX) p_timer->active++;

  if (p_timer->pm_action[timer_idx] < pm_action)
    p_timer->pm_action[timer_idx] = pm_action;

  p_timer->srvc_id[timer_idx] = srvc_id;
  state_lock.unlock();

  alarm_set_on_mloop(p_timer->timer[timer_idx], timeout_ms,
                     bta_dm_pm_timer_cback, p_timer->timer[timer_idx]);
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_stop_timer_by_index
 *
 * Description      stop a PM timer
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_stop_timer_by_index(tBTA_PM_TIMER* p_timer,
                                          uint8_t timer_idx) {
  if ((p_timer == NULL) || (timer_idx >= BTA_DM_PM_MODE_TIMER_MAX)) return;

  std::unique_lock<std::recursive_mutex> schedule_lock(pm_timer_schedule_mutex);
  std::unique_lock<std::recursive_mutex> state_lock(pm_timer_state_mutex);
  if (p_timer->srvc_id[timer_idx] == BTA_ID_MAX) {
    return;
  } /* The timer was not scheduled */

  ASSERT_LOG(p_timer->in_use,
             "Timer was not scheduled p_timer->srvc_id[timer_idx]:%hhu",
             p_timer->srvc_id[timer_idx]);
  ASSERT_LOG(p_timer->active > 0, "No tasks on timer are active");

  p_timer->srvc_id[timer_idx] = BTA_ID_MAX;
  /* NOTE: pm_action[timer_idx] intentionally not reset */

  p_timer->active--;
  if (p_timer->active == 0) p_timer->in_use = false;
  state_lock.unlock();

  alarm_cancel(p_timer->timer[timer_idx]);
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_cback
 *
 * Description      Conn change callback from sys for low power management
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_cback(tBTA_SYS_CONN_STATUS status, uint8_t id,
                            uint8_t app_id, const RawAddress& peer_addr) {
  uint8_t i, j;
  tBTA_DM_PEER_DEVICE* p_dev;
  tBTA_DM_PM_REQ pm_req = BTA_DM_PM_NEW_REQ;

  LOG_DEBUG("Power management callback status:%s[%hhu] id:%s[%d], app:%hhu",
            bta_sys_conn_status_text(status).c_str(), status,
            BtaIdSysText(id).c_str(), id, app_id);

  /* find if there is an power mode entry for the service */
  for (i = 1; i <= p_bta_dm_pm_cfg[0].app_id; i++) {
    if ((p_bta_dm_pm_cfg[i].id == id) &&
        ((p_bta_dm_pm_cfg[i].app_id == BTA_ALL_APP_ID) ||
         (p_bta_dm_pm_cfg[i].app_id == app_id)))
      break;
  }

  /* if no entries are there for the app_id and subsystem in
   * get_bta_dm_pm_spec()*/
  if (i > p_bta_dm_pm_cfg[0].app_id) {
    LOG_DEBUG("Ignoring power management callback as no service entries exist");
    return;
  }

  LOG_DEBUG("Stopped all timers for service to device:%s id:%hhu",
            ADDRESS_TO_LOGGABLE_CSTR(peer_addr), id);
  bta_dm_pm_stop_timer_by_srvc_id(peer_addr, id);

  p_dev = bta_dm_find_peer_device(peer_addr);
  if (p_dev) {
    LOG_DEBUG("Device info:%s", device_info_text(p_dev->Info()).c_str());
  } else {
    LOG_ERROR("Unable to find peer device...yet soldiering on...");
  }

  /* set SSR parameters on SYS CONN OPEN */
  int index = BTA_DM_PM_SSR0;
  if ((BTA_SYS_CONN_OPEN == status) && p_dev &&
      (p_dev->Info() & BTA_DM_DI_USE_SSR)) {
    index = get_bta_dm_pm_spec()[p_bta_dm_pm_cfg[i].spec_idx].ssr;
  } else if (BTA_ID_AV == id) {
    if (BTA_SYS_CONN_BUSY == status) {
      /* set SSR4 for A2DP on SYS CONN BUSY */
      index = BTA_DM_PM_SSR4;
    } else if (BTA_SYS_CONN_IDLE == status) {
      index = get_bta_dm_pm_spec()[p_bta_dm_pm_cfg[i].spec_idx].ssr;
    }
  }

  /* if no action for the event */
  if (get_bta_dm_pm_spec()[p_bta_dm_pm_cfg[i].spec_idx]
          .actn_tbl[status]
          .power_mode == BTA_DM_PM_NO_ACTION) {
    if (BTA_DM_PM_SSR0 == index) /* and do not need to set SSR, return. */
      return;
  }

  for (j = 0; j < bta_dm_conn_srvcs.count; j++) {
    /* check if an entry already present */
    if ((bta_dm_conn_srvcs.conn_srvc[j].id == id) &&
        (bta_dm_conn_srvcs.conn_srvc[j].app_id == app_id) &&
        bta_dm_conn_srvcs.conn_srvc[j].peer_bdaddr == peer_addr) {
      bta_dm_conn_srvcs.conn_srvc[j].new_request = true;
      break;
    }
  }

  /* if subsystem has no more preference on the power mode remove
 the cb */
  if (get_bta_dm_pm_spec()[p_bta_dm_pm_cfg[i].spec_idx]
          .actn_tbl[status]
          .power_mode == BTA_DM_PM_NO_PREF) {
    if (j != bta_dm_conn_srvcs.count) {
      bta_dm_conn_srvcs.count--;

      for (; j < bta_dm_conn_srvcs.count; j++) {
        memcpy(&bta_dm_conn_srvcs.conn_srvc[j],
               &bta_dm_conn_srvcs.conn_srvc[j + 1],
               sizeof(bta_dm_conn_srvcs.conn_srvc[j]));
      }
    } else {
      APPL_TRACE_WARNING("bta_dm_act no entry for connected service cbs");
      return;
    }
  } else if (j == bta_dm_conn_srvcs.count) {
    /* check if we have more connected service that cbs */
    if (bta_dm_conn_srvcs.count == BTA_DM_NUM_CONN_SRVS) {
      LOG_WARN("bta_dm_act no more connected service cbs");
      return;
    }

    /* fill in a new cb */
    bta_dm_conn_srvcs.conn_srvc[j].id = id;
    bta_dm_conn_srvcs.conn_srvc[j].app_id = app_id;
    bta_dm_conn_srvcs.conn_srvc[j].new_request = true;
    bta_dm_conn_srvcs.conn_srvc[j].peer_bdaddr = peer_addr;

    LOG_INFO("New connection service:%s[%hhu] app_id:%d",
             BtaIdSysText(id).c_str(), id, app_id);

    bta_dm_conn_srvcs.count++;
    bta_dm_conn_srvcs.conn_srvc[j].state = status;
  } else {
    /* no service is added or removed. only updating status. */
    bta_dm_conn_srvcs.conn_srvc[j].state = status;
  }

  /* stop timer */
  bta_dm_pm_stop_timer(peer_addr);
  if (bta_dm_conn_srvcs.count > 0) {
    pm_req = BTA_DM_PM_RESTART;
    APPL_TRACE_DEBUG(
        "%s bta_dm_pm_stop_timer for current service, restart other "
        "service timers: count = %d",
        __func__, bta_dm_conn_srvcs.count);
  }

  if (p_dev) {
    p_dev->pm_mode_attempted = 0;
    p_dev->pm_mode_failed = 0;
  }

  if (p_bta_dm_ssr_spec[index].max_lat || index == BTA_DM_PM_SSR_HH) {
    /* do not perform ssr for AVDTP start */
    if (id != BTA_ID_AV || status != BTA_SYS_CONN_BUSY) {
      bta_dm_pm_ssr(peer_addr, index);
    } else {
      LOG_DEBUG("%s: Do not perform SSR when AVDTP start", __func__);
    }
  } else {
    const controller_t* controller = controller_get_interface();
    uint8_t* p = NULL;
    if (controller->supports_sniff_subrating() &&
        ((NULL != (p = BTM_ReadRemoteFeatures(peer_addr))) &&
         HCI_SNIFF_SUB_RATE_SUPPORTED(p)) &&
        (index == BTA_DM_PM_SSR0)) {
      if (status == BTA_SYS_SCO_OPEN) {
        APPL_TRACE_DEBUG("%s: SCO inactive, reset SSR to zero", __func__);
        BTM_SetSsrParams(peer_addr, 0, 0, 0);
      } else if (status == BTA_SYS_SCO_CLOSE) {
        APPL_TRACE_DEBUG("%s: SCO active, back to old SSR", __func__);
        bta_dm_pm_ssr(peer_addr, BTA_DM_PM_SSR0);
      }
    }
  }

  bta_dm_pm_set_mode(peer_addr, BTA_DM_PM_NO_ACTION, pm_req);
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_set_mode
 *
 * Description      Set the power mode for the device
 *
 *
 * Returns          void
 *
 ******************************************************************************/

static void bta_dm_pm_set_mode(const RawAddress& peer_addr,
                               tBTA_DM_PM_ACTION pm_request,
                               tBTA_DM_PM_REQ pm_req) {
  tBTA_DM_PM_ACTION pm_action = BTA_DM_PM_NO_ACTION;
  uint64_t timeout_ms = 0;
  uint8_t i, j;
  tBTA_DM_PM_ACTION failed_pm = 0;
  tBTA_DM_PEER_DEVICE* p_peer_device = NULL;
  tBTA_DM_PM_ACTION allowed_modes = 0;
  tBTA_DM_PM_ACTION pref_modes = 0;
  const tBTA_DM_PM_CFG* p_pm_cfg;
  const tBTA_DM_PM_SPEC* p_pm_spec;
  const tBTA_DM_PM_ACTN* p_act0;
  tBTA_DM_SRVCS* p_srvcs = NULL;
  bool timer_started = false;
  uint8_t timer_idx, available_timer = BTA_DM_PM_MODE_TIMER_MAX;
  uint64_t remaining_ms = 0;

  if (!bta_dm_cb.device_list.count) {
    LOG_INFO("Device list count is zero");
    return;
  }

  /* see if any attempt to put device in low power mode failed */
  p_peer_device = bta_dm_find_peer_device(peer_addr);
  /* if no peer device found return */
  if (p_peer_device == NULL) {
    LOG_INFO("No peer device found");
    return;
  }

  failed_pm = p_peer_device->pm_mode_failed;

  for (i = 0; i < bta_dm_conn_srvcs.count; i++) {
    p_srvcs = &bta_dm_conn_srvcs.conn_srvc[i];
    if (p_srvcs->peer_bdaddr == peer_addr) {
      /* p_bta_dm_pm_cfg[0].app_id is the number of entries */
      for (j = 1; j <= p_bta_dm_pm_cfg[0].app_id; j++) {
        if ((p_bta_dm_pm_cfg[j].id == p_srvcs->id) &&
            ((p_bta_dm_pm_cfg[j].app_id == BTA_ALL_APP_ID) ||
             (p_bta_dm_pm_cfg[j].app_id == p_srvcs->app_id)))
          break;
      }

      p_pm_cfg = &p_bta_dm_pm_cfg[j];
      p_pm_spec = &get_bta_dm_pm_spec()[p_pm_cfg->spec_idx];
      p_act0 = &p_pm_spec->actn_tbl[p_srvcs->state];

      allowed_modes |= p_pm_spec->allow_mask;
      LOG_DEBUG(
          "Service:%s[%hhu] state:%s[%hhu] allowed_modes:0x%02x "
          "service_index:%hhu ",
          BtaIdSysText(p_srvcs->id).c_str(), p_srvcs->id,
          bta_sys_conn_status_text(p_srvcs->state).c_str(), p_srvcs->state,
          allowed_modes, j);

      /* PM actions are in the order of strictness */

      /* first check if the first preference is ok */
      if (!(failed_pm & p_act0->power_mode)) {
        pref_modes |= p_act0->power_mode;

        if (p_act0->power_mode >= pm_action) {
          pm_action = p_act0->power_mode;

          if (pm_req != BTA_DM_PM_NEW_REQ || p_srvcs->new_request) {
            p_srvcs->new_request = false;
            timeout_ms = p_act0->timeout;
          }
        }
      }
    }
  }

  if (pm_action & (BTA_DM_PM_PARK | BTA_DM_PM_SNIFF)) {
    /* some service don't like the mode */
    if (!(allowed_modes & pm_action)) {
      /* select the other mode if its allowed and preferred, otherwise 0 which
       * is BTA_DM_PM_NO_ACTION */
      pm_action =
          (allowed_modes & (BTA_DM_PM_PARK | BTA_DM_PM_SNIFF) & pref_modes);

      /* no timeout needed if no action is required */
      if (pm_action == BTA_DM_PM_NO_ACTION) {
        timeout_ms = 0;
      }
    }
  }
  /* if need to start a timer */
  if ((pm_req != BTA_DM_PM_EXECUTE) && (timeout_ms > 0)) {
    for (i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
      if (bta_dm_cb.pm_timer[i].in_use &&
          bta_dm_cb.pm_timer[i].peer_bdaddr == peer_addr) {
        timer_idx = bta_pm_action_to_timer_idx(pm_action);
        if (timer_idx != BTA_DM_PM_MODE_TIMER_MAX) {
          remaining_ms =
              alarm_get_remaining_ms(bta_dm_cb.pm_timer[i].timer[timer_idx]);
          if (remaining_ms < timeout_ms) {
            /* Cancel and restart the timer */
            /*
             * TODO: The value of pm_action[timer_idx] is
             * conditionally updated between the two function
             * calls below when the timer is restarted.
             * This logic is error-prone and should be eliminated
             * in the future.
             */
            bta_dm_pm_stop_timer_by_index(&bta_dm_cb.pm_timer[i], timer_idx);
            bta_dm_pm_start_timer(&bta_dm_cb.pm_timer[i], timer_idx, timeout_ms,
                                  p_srvcs->id, pm_action);
          }
          timer_started = true;
        }
        break;
      } else if (!bta_dm_cb.pm_timer[i].in_use) {
        if (available_timer == BTA_DM_PM_MODE_TIMER_MAX) available_timer = i;
      }
    }
    /* new power mode for a new active connection */
    if (!timer_started) {
      if (available_timer != BTA_DM_PM_MODE_TIMER_MAX) {
        bta_dm_cb.pm_timer[available_timer].peer_bdaddr = peer_addr;
        timer_idx = bta_pm_action_to_timer_idx(pm_action);
        if (timer_idx != BTA_DM_PM_MODE_TIMER_MAX) {
          bta_dm_pm_start_timer(&bta_dm_cb.pm_timer[available_timer], timer_idx,
                                timeout_ms, p_srvcs->id, pm_action);
          timer_started = true;
        }
      } else {
        LOG_WARN("no more timers");
      }
    }
    return;
  }
  /* if pending power mode timer expires, and currecnt link is in a
     lower power mode than current profile requirement, igonre it */
  if (pm_req == BTA_DM_PM_EXECUTE && pm_request < pm_action) {
    LOG_ERROR("Ignore the power mode request: %d", pm_request);
    return;
  }
  if (pm_action == BTA_DM_PM_PARK) {
    p_peer_device->pm_mode_attempted = BTA_DM_PM_PARK;
    bta_dm_pm_park(peer_addr);
    LOG_WARN("DEPRECATED Setting link to park mode peer:%s",
             ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
  } else if (pm_action & BTA_DM_PM_SNIFF) {
    /* dont initiate SNIFF, if link_policy has it disabled */
    if (BTM_is_sniff_allowed_for(peer_addr)) {
      LOG_DEBUG(
          "Link policy allows sniff mode so setting mode "
          "peer:%s",
          ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
      p_peer_device->pm_mode_attempted = BTA_DM_PM_SNIFF;
      bta_dm_pm_sniff(p_peer_device, (uint8_t)(pm_action & 0x0F));
    } else {
      LOG_DEBUG("Link policy disallows sniff mode, ignore request peer:%s",
                ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
    }
  } else if (pm_action == BTA_DM_PM_ACTIVE) {
    LOG_DEBUG("Setting link to active mode peer:%s",
              ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
    bta_dm_pm_active(peer_addr);
  }
}
/*******************************************************************************
 *
 * Function         bta_ag_pm_park
 *
 * Description      Switch to park mode.
 *
 *
 * Returns          true if park attempted, false otherwise.
 *
 ******************************************************************************/
static bool bta_dm_pm_park(const RawAddress& peer_addr) {
  tBTM_PM_MODE mode = BTM_PM_STS_ACTIVE;

  /* if not in park mode, switch to park */
  if (!BTM_ReadPowerMode(peer_addr, &mode)) {
    LOG_WARN("Unable to read power mode for peer:%s",
             ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
  }

  if (mode != BTM_PM_MD_PARK) {
    tBTM_STATUS status = BTM_SetPowerMode(bta_dm_cb.pm_id, peer_addr,
                                          &p_bta_dm_pm_md[BTA_DM_PM_PARK_IDX]);
    if (status == BTM_CMD_STORED || status == BTM_CMD_STARTED) {
      return true;
    }
    LOG_WARN("Unable to set park power mode");
  }
  return true;
}
/*******************************************************************************
 *
 * Function         get_sniff_entry
 *
 * Description      Helper function to get sniff entry from sysprop or
 *                  default table.
 *
 *
 * Returns          tBTM_PM_PWR_MD with specified |index|.
 *
 ******************************************************************************/
tBTM_PM_PWR_MD get_sniff_entry(uint8_t index) {
  static std::vector<tBTM_PM_PWR_MD> pwr_mds_cache;
  if (pwr_mds_cache.size() == BTA_DM_PM_PARK_IDX) {
    if (index >= BTA_DM_PM_PARK_IDX) {
      return pwr_mds_cache[0];
    }
    return pwr_mds_cache[index];
  }

  std::vector<uint32_t> invalid_list(BTA_DM_PM_PARK_IDX, 0);
  std::vector<uint32_t> max =
      osi_property_get_uintlist(kPropertySniffMaxIntervals, invalid_list);
  std::vector<uint32_t> min =
      osi_property_get_uintlist(kPropertySniffMinIntervals, invalid_list);
  std::vector<uint32_t> attempt =
      osi_property_get_uintlist(kPropertySniffAttempts, invalid_list);
  std::vector<uint32_t> timeout =
      osi_property_get_uintlist(kPropertySniffTimeouts, invalid_list);

  // If any of the sysprops are malformed or don't exist, use default table
  // value
  bool use_defaults =
      (max.size() < BTA_DM_PM_PARK_IDX || max == invalid_list ||
       min.size() < BTA_DM_PM_PARK_IDX || min == invalid_list ||
       attempt.size() < BTA_DM_PM_PARK_IDX || attempt == invalid_list ||
       timeout.size() < BTA_DM_PM_PARK_IDX || timeout == invalid_list);

  for (auto i = 0; i < BTA_DM_PM_PARK_IDX; i++) {
    if (use_defaults) {
      pwr_mds_cache.push_back(p_bta_dm_pm_md[i]);
    } else {
      pwr_mds_cache.push_back(tBTM_PM_PWR_MD{
          static_cast<uint16_t>(max[i]), static_cast<uint16_t>(min[i]),
          static_cast<uint16_t>(attempt[i]), static_cast<uint16_t>(timeout[i]),
          BTM_PM_MD_SNIFF});
    }
  }

  if (index >= BTA_DM_PM_PARK_IDX) {
    return pwr_mds_cache[0];
  }
  return pwr_mds_cache[index];
}
/*******************************************************************************
 *
 * Function         bta_ag_pm_sniff
 *
 * Description      Switch to sniff mode.
 *
 *
 * Returns          true if sniff attempted, false otherwise.
 *
 ******************************************************************************/
void bta_dm_pm_sniff(tBTA_DM_PEER_DEVICE* p_peer_dev, uint8_t index) {
  tBTM_PM_MODE mode = BTM_PM_MD_ACTIVE;
  tBTM_PM_PWR_MD pwr_md;
  tBTM_STATUS status;

  if (!BTM_ReadPowerMode(p_peer_dev->peer_bdaddr, &mode)) {
    LOG_WARN("Unable to read power mode for peer:%s",
             ADDRESS_TO_LOGGABLE_CSTR(p_peer_dev->peer_bdaddr));
  }
  tBTM_PM_STATUS mode_status = static_cast<tBTM_PM_STATUS>(mode);
  LOG_DEBUG("Current power mode:%s[0x%x] peer_info:%s[0x%02x]",
            power_mode_status_text(mode_status).c_str(), mode_status,
            device_info_text(p_peer_dev->Info()).c_str(), p_peer_dev->Info());

  uint8_t* p_rem_feat = BTM_ReadRemoteFeatures(p_peer_dev->peer_bdaddr);

  const controller_t* controller = controller_get_interface();
  if (mode != BTM_PM_MD_SNIFF ||
      (controller->supports_sniff_subrating() && p_rem_feat &&
       HCI_SNIFF_SUB_RATE_SUPPORTED(p_rem_feat) &&
       !(p_peer_dev->Info() & BTA_DM_DI_USE_SSR))) {
    /* Dont initiate Sniff if controller has alreay accepted
     * remote sniff params. This avoid sniff loop issue with
     * some agrresive headsets who use sniff latencies more than
     * DUT supported range of Sniff intervals.*/
    if ((mode == BTM_PM_MD_SNIFF) &&
        (p_peer_dev->Info() & BTA_DM_DI_ACP_SNIFF)) {
      LOG_DEBUG("Link already in sniff mode peer:%s",
                ADDRESS_TO_LOGGABLE_CSTR(p_peer_dev->peer_bdaddr));
      return;
    }
  }
  /* if the current mode is not sniff, issue the sniff command.
   * If sniff, but SSR is not used in this link, still issue the command */
  tBTM_PM_PWR_MD sniff_entry = get_sniff_entry(index);
  memcpy(&pwr_md, &sniff_entry, sizeof(tBTM_PM_PWR_MD));
  if (p_peer_dev->Info() & BTA_DM_DI_INT_SNIFF) {
    LOG_DEBUG("Trying to force power mode");
    pwr_md.mode |= BTM_PM_MD_FORCE;
  }
  status = BTM_SetPowerMode(bta_dm_cb.pm_id, p_peer_dev->peer_bdaddr, &pwr_md);
  if (status == BTM_CMD_STORED || status == BTM_CMD_STARTED) {
    p_peer_dev->info &= ~(BTA_DM_DI_INT_SNIFF | BTA_DM_DI_ACP_SNIFF);
    p_peer_dev->info |= BTA_DM_DI_SET_SNIFF;
  } else if (status == BTM_SUCCESS) {
    APPL_TRACE_DEBUG("bta_dm_pm_sniff BTM_SetPowerMode() returns BTM_SUCCESS");
    p_peer_dev->info &=
        ~(BTA_DM_DI_INT_SNIFF | BTA_DM_DI_ACP_SNIFF | BTA_DM_DI_SET_SNIFF);
  } else {
    LOG_ERROR("Unable to set power mode peer:%s status:%s",
              ADDRESS_TO_LOGGABLE_CSTR(p_peer_dev->peer_bdaddr),
              btm_status_text(status).c_str());
    p_peer_dev->info &=
        ~(BTA_DM_DI_INT_SNIFF | BTA_DM_DI_ACP_SNIFF | BTA_DM_DI_SET_SNIFF);
  }
}
/*******************************************************************************
 *
 * Function         bta_dm_pm_ssr
 *
 * Description      checks and sends SSR parameters
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_ssr(const RawAddress& peer_addr, const int ssr) {
  int ssr_index = ssr;
  tBTA_DM_SSR_SPEC* p_spec = &p_bta_dm_ssr_spec[ssr];

  LOG_DEBUG("Request to put link to device:%s into power_mode:%s",
            ADDRESS_TO_LOGGABLE_CSTR(peer_addr), p_spec->name);
  /* go through the connected services */
  for (int i = 0; i < bta_dm_conn_srvcs.count; i++) {
    const tBTA_DM_SRVCS& service = bta_dm_conn_srvcs.conn_srvc[i];
    if (service.peer_bdaddr != peer_addr) {
      continue;
    }
    /* p_bta_dm_pm_cfg[0].app_id is the number of entries */
    int current_ssr_index = BTA_DM_PM_SSR0;
    for (int j = 1; j <= p_bta_dm_pm_cfg[0].app_id; j++) {
      /* find the associated p_bta_dm_pm_cfg */
      const tBTA_DM_PM_CFG& config = p_bta_dm_pm_cfg[j];
      current_ssr_index = get_bta_dm_pm_spec()[config.spec_idx].ssr;
      if ((config.id == service.id) && ((config.app_id == BTA_ALL_APP_ID) ||
                                        (config.app_id == service.app_id))) {
        LOG_INFO("Found connected service:%s app_id:%d peer:%s spec_name:%s",
                 BtaIdSysText(service.id).c_str(), service.app_id,
                 ADDRESS_TO_LOGGABLE_CSTR(peer_addr),
                 p_bta_dm_ssr_spec[current_ssr_index].name);
        break;
      }
    }
    /* find the ssr index with the smallest max latency. */
    tBTA_DM_SSR_SPEC* p_spec_cur = &p_bta_dm_ssr_spec[current_ssr_index];
    /* HH has the per connection SSR preference, already read the SSR params
     * from BTA HH */
    if (current_ssr_index == BTA_DM_PM_SSR_HH) {
      if (GetInterfaceToProfiles()->profileSpecific_HACK->bta_hh_read_ssr_param(
              peer_addr, &p_spec_cur->max_lat, &p_spec_cur->min_rmt_to) ==
          BTA_HH_ERR) {
        continue;
      }
    }
    if (p_spec_cur->max_lat < p_spec->max_lat ||
        (ssr_index == BTA_DM_PM_SSR0 && current_ssr_index != BTA_DM_PM_SSR0)) {
      LOG_DEBUG(
          "Changing sniff subrating specification for %s from %s[%d] ==> "
          "%s[%d]",
          ADDRESS_TO_LOGGABLE_CSTR(peer_addr), p_spec->name, ssr_index, p_spec_cur->name,
          current_ssr_index);
      ssr_index = current_ssr_index;
      p_spec = &p_bta_dm_ssr_spec[ssr_index];
    }
  }

  if (p_spec->max_lat) {
    /* Avoid SSR reset on device which has SCO connected */
    int idx = bta_dm_get_sco_index();
    if (idx != -1) {
      if (bta_dm_conn_srvcs.conn_srvc[idx].peer_bdaddr == peer_addr) {
        LOG_WARN("SCO is active on device, ignore SSR");
        return;
      }
    }

    LOG_DEBUG(
        "Setting sniff subrating for device:%s spec_name:%s max_latency(s):%.2f"
        " min_local_timeout(s):%.2f min_remote_timeout(s):%.2f",
        ADDRESS_TO_LOGGABLE_CSTR(peer_addr), p_spec->name,
        ticks_to_seconds(p_spec->max_lat), ticks_to_seconds(p_spec->min_loc_to),
        ticks_to_seconds(p_spec->min_rmt_to));
    /* set the SSR parameters. */
    BTM_SetSsrParams(peer_addr, p_spec->max_lat, p_spec->min_rmt_to,
                     p_spec->min_loc_to);
  }
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_active
 *
 * Description      Brings connection to active mode
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_dm_pm_active(const RawAddress& peer_addr) {
  tBTM_PM_PWR_MD pm{
      .mode = BTM_PM_MD_ACTIVE,
  };

  /* switch to active mode */
  tBTM_STATUS status = BTM_SetPowerMode(bta_dm_cb.pm_id, peer_addr, &pm);
  switch (status) {
    case BTM_CMD_STORED:
      LOG_DEBUG("Active power mode stored for execution later for remote:%s",
                ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
      break;
    case BTM_CMD_STARTED:
      LOG_DEBUG("Active power mode started for remote:%s",
                ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
      break;
    case BTM_SUCCESS:
      LOG_DEBUG("Active power mode already set for device:%s",
                ADDRESS_TO_LOGGABLE_CSTR(peer_addr));
      break;
    default:
      LOG_WARN("Unable to set active power mode for device:%s status:%s",
               ADDRESS_TO_LOGGABLE_CSTR(peer_addr), btm_status_text(status).c_str());
      break;
  }
}

/** BTM power manager callback */
static void bta_dm_pm_btm_cback(const RawAddress& bd_addr,
                                tBTM_PM_STATUS status, uint16_t value,
                                tHCI_STATUS hci_status) {
  do_in_main_thread(FROM_HERE, base::Bind(bta_dm_pm_btm_status, bd_addr, status,
                                          value, hci_status));
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_timer_cback
 *
 * Description      Power management timer callback.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
static void bta_dm_pm_timer_cback(void* data) {
  uint8_t i, j;
  alarm_t* alarm = (alarm_t*)data;

  std::unique_lock<std::recursive_mutex> state_lock(pm_timer_state_mutex);
  for (i = 0; i < BTA_DM_NUM_PM_TIMER; i++) {
    APPL_TRACE_DEBUG("dm_pm_timer[%d] in use? %d", i,
                     bta_dm_cb.pm_timer[i].in_use);
    if (bta_dm_cb.pm_timer[i].in_use) {
      for (j = 0; j < BTA_DM_PM_MODE_TIMER_MAX; j++) {
        if (bta_dm_cb.pm_timer[i].timer[j] == alarm) {
          bta_dm_cb.pm_timer[i].active--;
          bta_dm_cb.pm_timer[i].srvc_id[j] = BTA_ID_MAX;
          APPL_TRACE_DEBUG("dm_pm_timer[%d] expires, timer_idx=%d", i, j);
          break;
        }
      }
      if (bta_dm_cb.pm_timer[i].active == 0)
        bta_dm_cb.pm_timer[i].in_use = false;
      if (j < BTA_DM_PM_MODE_TIMER_MAX) break;
    }
  }
  state_lock.unlock();

  /* no more timers */
  if (i == BTA_DM_NUM_PM_TIMER) return;

  do_in_main_thread(
      FROM_HERE, base::Bind(bta_dm_pm_timer, bta_dm_cb.pm_timer[i].peer_bdaddr,
                            bta_dm_cb.pm_timer[i].pm_action[j]));
}

/** Process pm status event from btm */
void bta_dm_pm_btm_status(const RawAddress& bd_addr, tBTM_PM_STATUS status,
                          uint16_t interval, tHCI_STATUS hci_status) {
  LOG_DEBUG(
      "Power mode notification event status:%s peer:%s interval:%hu "
      "hci_status:%s",
      power_mode_status_text(status).c_str(), ADDRESS_TO_LOGGABLE_CSTR(bd_addr),
      interval, hci_error_code_text(hci_status).c_str());

  tBTA_DM_PEER_DEVICE* p_dev = bta_dm_find_peer_device(bd_addr);
  if (p_dev == nullptr) {
    LOG_INFO("Unable to process power event for peer:%s",
             ADDRESS_TO_LOGGABLE_CSTR(bd_addr));
    return;
  }

  tBTA_DM_DEV_INFO info = p_dev->Info();
  /* check new mode */
  switch (status) {
    case BTM_PM_STS_ACTIVE:
      /* if our sniff or park attempt failed
      we should not try it again*/
      if (hci_status != 0) {
        APPL_TRACE_ERROR("%s hci_status=%d", __func__, hci_status);
        p_dev->info &=
            ~(BTA_DM_DI_INT_SNIFF | BTA_DM_DI_ACP_SNIFF | BTA_DM_DI_SET_SNIFF);

        if (p_dev->pm_mode_attempted & (BTA_DM_PM_PARK | BTA_DM_PM_SNIFF)) {
          p_dev->pm_mode_failed |=
              ((BTA_DM_PM_PARK | BTA_DM_PM_SNIFF) & p_dev->pm_mode_attempted);
          bta_dm_pm_stop_timer_by_mode(bd_addr, p_dev->pm_mode_attempted);
          bta_dm_pm_set_mode(bd_addr, BTA_DM_PM_NO_ACTION, BTA_DM_PM_RESTART);
        }
      } else {
        if (p_dev->prev_low) {
          /* need to send the SSR paramaters to controller again */
          bta_dm_pm_ssr(p_dev->peer_bdaddr, BTA_DM_PM_SSR0);
        }
        p_dev->prev_low = BTM_PM_STS_ACTIVE;
        /* link to active mode, need to restart the timer for next low power
         * mode if needed */
        bta_dm_pm_stop_timer(bd_addr);
        bta_dm_pm_set_mode(bd_addr, BTA_DM_PM_NO_ACTION, BTA_DM_PM_RESTART);
      }
      break;

    case BTM_PM_STS_PARK:
    case BTM_PM_STS_HOLD:
      /* save the previous low power mode - for SSR.
       * SSR parameters are sent to controller on "conn open".
       * the numbers stay good until park/hold/detach */
      if (p_dev->info & BTA_DM_DI_USE_SSR) p_dev->prev_low = status;
      break;

    case BTM_PM_STS_SSR:
      if (hci_status != 0) {
        LOG_WARN("Received error when attempting to set sniff subrating mode");
      }
      if (interval) {
        p_dev->info |= BTA_DM_DI_USE_SSR;
        LOG_DEBUG("Enabling sniff subrating mode for peer:%s",
                  ADDRESS_TO_LOGGABLE_CSTR(bd_addr));
      } else {
        p_dev->info &= ~BTA_DM_DI_USE_SSR;
        LOG_DEBUG("Disabling sniff subrating mode for peer:%s",
                  ADDRESS_TO_LOGGABLE_CSTR(bd_addr));
      }
      break;
    case BTM_PM_STS_SNIFF:
      if (hci_status == 0) {
        /* Stop PM timer now if already active for
         * particular device since link is already
         * put in sniff mode by remote device, and
         * PM timer sole purpose is to put the link
         * in sniff mode from host side.
         */
        bta_dm_pm_stop_timer(bd_addr);
      } else {
        p_dev->info &=
            ~(BTA_DM_DI_SET_SNIFF | BTA_DM_DI_INT_SNIFF | BTA_DM_DI_ACP_SNIFF);
        if (info & BTA_DM_DI_SET_SNIFF)
          p_dev->info |= BTA_DM_DI_INT_SNIFF;
        else
          p_dev->info |= BTA_DM_DI_ACP_SNIFF;
      }
      break;

    case BTM_PM_STS_ERROR:
      p_dev->info &= ~BTA_DM_DI_SET_SNIFF;
      break;

    default:
      LOG_ERROR("Received unknown power mode status event:%hhu", status);
      break;
      }
}

/** Process pm timer event from btm */
void bta_dm_pm_timer(const RawAddress& bd_addr, tBTA_DM_PM_ACTION pm_request) {
  APPL_TRACE_EVENT("%s", __func__);
  bta_dm_pm_set_mode(bd_addr, pm_request, BTA_DM_PM_EXECUTE);
}

/*******************************************************************************
 *
 * Function         bta_dm_find_peer_device
 *
 * Description      Given an address, find the associated control block.
 *
 * Returns          tBTA_DM_PEER_DEVICE
 *
 ******************************************************************************/
tBTA_DM_PEER_DEVICE* bta_dm_find_peer_device(const RawAddress& peer_addr) {
  tBTA_DM_PEER_DEVICE* p_dev = NULL;

  for (int i = 0; i < bta_dm_cb.device_list.count; i++) {
    if (bta_dm_cb.device_list.peer_device[i].peer_bdaddr == peer_addr) {
      p_dev = &bta_dm_cb.device_list.peer_device[i];
      break;
    }
  }
  return p_dev;
}

/*******************************************************************************
 *
 * Function        bta_dm_get_sco_index
 *
 * Description     Loop through connected services for HFP+State=SCO
 *
 * Returns         index at which SCO is connected, in absence of SCO return -1
 *
 ******************************************************************************/
static int bta_dm_get_sco_index() {
  for (int j = 0; j < bta_dm_conn_srvcs.count; j++) {
    /* check for SCO connected index */
    if ((bta_dm_conn_srvcs.conn_srvc[j].id == BTA_ID_AG) &&
        (bta_dm_conn_srvcs.conn_srvc[j].state == BTA_SYS_SCO_OPEN)) {
      return j;
    }
  }
  return -1;
}

/*******************************************************************************
 *
 * Function         bta_dm_pm_obtain_controller_state
 *
 * Description      This function obtains the consolidated controller power
 *                  state
 *
 * Parameters:
 *
 ******************************************************************************/
tBTM_CONTRL_STATE bta_dm_pm_obtain_controller_state(void) {
  /*   Did not use counts as it is not sure, how accurate the count values are
   *in
   **  bta_dm_cb.device_list.count > 0 || bta_dm_cb.device_list.le_count > 0 */

  tBTM_CONTRL_STATE cur_state = BTM_CONTRL_UNKNOWN;
  cur_state = BTM_PM_ReadControllerState();

  APPL_TRACE_DEBUG("bta_dm_pm_obtain_controller_state: %d", cur_state);
  return cur_state;
}
