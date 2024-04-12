# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""L2CAP grpc interface."""

import asyncio
import logging
import os
import socket as socket_module

from floss.pandora.floss import floss_enums
from floss.pandora.floss import socket_manager
from floss.pandora.floss import utils
from floss.pandora.server import bluetooth as bluetooth_module
from google.protobuf import empty_pb2
import grpc
from pandora_experimental import l2cap_grpc_aio
from pandora_experimental import l2cap_pb2


class L2CAPService(l2cap_grpc_aio.L2CAPServicer):
    """Service to trigger Bluetooth L2CAP procedures.

    This class implements the Pandora bluetooth test interfaces,
    where the meta class definition is automatically generated by the protobuf.
    The interface definition can be found in:
    https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Bluetooth/pandora/interfaces/pandora_experimental/l2cap.proto
    """

    # Size of the buffer for data transactions.
    BUFFER_SIZE = 512

    def __init__(self, bluetooth: bluetooth_module.Bluetooth):
        self.bluetooth = bluetooth

        # key = connection_value, val = (socket_id, [stream])
        self.incoming_connections = dict()

        # key = connection_value, val = current_index
        # In L2CAP/COS/ECFC/BV-03-C an incoming connection_value could be associated with multiple streams and the
        # streams would be used in an interleave manner. This dict records the current index and would be updated each
        # time SendData and ReceiveData are called.
        self.interleave_connection_current_index = dict()

        # key = connection_value, val = stream
        self.outgoing_connections = dict()

    async def ListenL2CAPChannel(self, request: l2cap_pb2.ListenL2CAPChannelRequest,
                                 context: grpc.ServicerContext) -> l2cap_pb2.ListenL2CAPChannelResponse:

        class ListenL2CAPObserver(socket_manager.SocketManagerCallbacks):
            """Observer to observe listening on the L2CAP channel."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_incoming_socket_ready(self, socket, status):
                if not socket or 'id' not in socket:
                    return

                socket_id = socket['id']
                if socket_id != self.task['socket_id']:
                    return

                if status is None or floss_enums.BtStatus(status) != floss_enums.BtStatus.SUCCESS:
                    logging.error('Failed to listen on the L2CAP channel with socket_id: %s. Status: %s', socket_id,
                                  status)

                future = self.task['listen_l2cap_channel']
                future.get_loop().call_soon_threadsafe(future.set_result, (status, socket))

        try:
            if not request.secure:
                channel_type = 'insecure'
                socket_result = self.bluetooth.listen_using_insecure_l2cap_channel()
            else:
                channel_type = 'secure'
                socket_result = self.bluetooth.listen_using_l2cap_channel()

            if socket_result is None:
                await context.abort(grpc.StatusCode.INTERNAL,
                                    f'Failed to call listen_using_{channel_type}_l2cap_channel.')

            socket_id = socket_result['id']
            l2cap_channel_listener = {
                'listen_l2cap_channel': asyncio.get_running_loop().create_future(),
                'socket_id': socket_id
            }
            observer = ListenL2CAPObserver(l2cap_channel_listener)
            name = utils.create_observer_name(observer)
            self.bluetooth.socket_manager.register_callback_observer(name, observer)
            status, socket = await asyncio.wait_for(l2cap_channel_listener['listen_l2cap_channel'], timeout=5)
            if status != floss_enums.BtStatus.SUCCESS:
                await context.abort(
                    grpc.StatusCode.INTERNAL,
                    f'Failed to listen on the L2CAP channel with socket_id: {socket_id}. Status: {status}')

            self.incoming_connections[request.connection.cookie.value] = (socket['id'], [])
        finally:
            self.bluetooth.socket_manager.unregister_callback_observer(name, observer)

        return empty_pb2.Empty()

    async def CreateLECreditBasedChannel(self, request: l2cap_pb2.CreateLECreditBasedChannelRequest,
                                         context: grpc.ServicerContext) -> l2cap_pb2.CreateLECreditBasedChannelResponse:

        class CreateL2CAPObserver(socket_manager.SocketManagerCallbacks):
            """Observer to observe the creation of the L2CAP channel."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_outgoing_connection_result(self, connecting_id, result, socket, *, dbus_unix_fd_list=None):
                if connecting_id != self.task['connecting_id']:
                    return

                future = self.task['create_l2cap_channel']
                if result is None or floss_enums.BtStatus(result) != floss_enums.BtStatus.SUCCESS:
                    logging.error('Failed to create the L2CAP channel with connecting_id: %s. Status: %s',
                                  connecting_id, result)
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                if not socket:
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                optional_fd = socket['optional_value']['fd']
                if not optional_fd:
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                if not dbus_unix_fd_list or dbus_unix_fd_list.get_length() < 1:
                    logging.error('on_outgoing_connection_result: Empty fd list')
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                fd_handle = optional_fd['optional_value']
                if fd_handle > dbus_unix_fd_list.get_length():
                    logging.error('on_outgoing_connection_result: Invalid fd handle')
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                fd = dbus_unix_fd_list.get(fd_handle)
                fd_dup = os.dup(fd)
                future.get_loop().call_soon_threadsafe(future.set_result, fd_dup)

        connection_value = request.connection.cookie.value
        address = utils.address_from(connection_value)
        try:
            if not request.secure:
                channel_type = 'insecure'
                socket_result = self.bluetooth.create_insecure_l2cap_channel(address, request.psm)
            else:
                channel_type = 'secure'
                socket_result = self.bluetooth.create_l2cap_channel(address, request.psm)

            if socket_result is None:
                await context.abort(grpc.StatusCode.INTERNAL, f'Failed to call create_{channel_type}_l2cap_channel.')

            connecting_id = socket_result['id']
            l2cap_channel_creation = {
                'create_l2cap_channel': asyncio.get_running_loop().create_future(),
                'connecting_id': connecting_id
            }
            observer = CreateL2CAPObserver(l2cap_channel_creation)
            name = utils.create_observer_name(observer)
            self.bluetooth.socket_manager.register_callback_observer(name, observer)
            fd = await asyncio.wait_for(l2cap_channel_creation['create_l2cap_channel'], timeout=5)
            if fd is None:
                await context.abort(grpc.StatusCode.INTERNAL,
                                    f'Failed to get the fd from L2CAP socket with connecting_id: {connecting_id}')

            stream = socket_module.fromfd(fd, socket_module.AF_UNIX, socket_module.SOCK_STREAM)
            self.outgoing_connections[connection_value] = stream
        finally:
            self.bluetooth.socket_manager.unregister_callback_observer(name, observer)

        return empty_pb2.Empty()

    async def AcceptL2CAPChannel(self, request: l2cap_pb2.AcceptL2CAPChannelRequest,
                                 context: grpc.ServicerContext) -> l2cap_pb2.AcceptL2CAPChannelResponse:

        class AcceptL2CAPObserver(socket_manager.SocketManagerCallbacks):
            """Observer to observe the acceptance of the L2CAP channel."""

            def __init__(self, task):
                self.task = task

            @utils.glib_callback()
            def on_handle_incoming_connection(self, listener_id, connection, *, dbus_unix_fd_list=None):
                if listener_id != self.task['listener_id']:
                    return

                future = self.task['accept_l2cap_channel']
                if not connection:
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                optional_fd = connection['fd']
                if not optional_fd:
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                if not dbus_unix_fd_list or dbus_unix_fd_list.get_length() < 1:
                    logging.error('on_handle_incoming_connection: Empty fd list')
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                fd_handle = optional_fd['optional_value']
                if fd_handle > dbus_unix_fd_list.get_length():
                    logging.error('on_handle_incoming_connection: Invalid fd handle')
                    future.get_loop().call_soon_threadsafe(future.set_result, None)
                    return

                fd = dbus_unix_fd_list.get(fd_handle)
                fd_dup = os.dup(fd)
                future.get_loop().call_soon_threadsafe(future.set_result, fd_dup)

        connection_value = request.connection.cookie.value
        socket_tuple = self.incoming_connections.get(connection_value)
        if socket_tuple is None:
            return empty_pb2.Empty()

        try:
            socket_id, incoming_streams = socket_tuple
            l2cap_channel_acceptance = {
                'accept_l2cap_channel': asyncio.get_running_loop().create_future(),
                'listener_id': socket_id
            }
            observer = AcceptL2CAPObserver(l2cap_channel_acceptance)
            name = utils.create_observer_name(observer)
            self.bluetooth.socket_manager.register_callback_observer(name, observer)
            accept_socket_status = self.bluetooth.accept_socket(socket_id, timeout_ms=5)
            if accept_socket_status != floss_enums.BtStatus.SUCCESS:
                await context.abort(
                    grpc.StatusCode.INTERNAL,
                    f'Failed to accept the L2CAP socket with socket_id: {socket_id}. Status: {accept_socket_status}.')

            fd = await asyncio.wait_for(l2cap_channel_acceptance['accept_l2cap_channel'], timeout=5)
            if fd is None:
                await context.abort(grpc.StatusCode.INTERNAL,
                                    f'Failed to get the fd from L2CAP socket with socket_id: {socket_id}')

            stream = socket_module.fromfd(fd, socket_module.AF_UNIX, socket_module.SOCK_STREAM)
            incoming_streams.append(stream)
        finally:
            self.bluetooth.socket_manager.unregister_callback_observer(name, observer)

        return empty_pb2.Empty()

    def _interleave_get_incoming_stream(self, connection_value):
        """Gets the incoming stream in an interleave manner."""
        socket_tuple = self.incoming_connections.get(connection_value)
        if socket_tuple is None:
            logging.error('Invalid connection_value: %s', connection_value)
            return None

        _, streams = socket_tuple
        if not streams:
            logging.error('No incoming stream available for connection_value: %s', connection_value)
            return None

        current_index = self.interleave_connection_current_index.get(connection_value, 0)
        self.interleave_connection_current_index[connection_value] = (current_index + 1) % len(streams)
        return streams[current_index]

    async def SendData(self, request: l2cap_pb2.SendDataRequest,
                       context: grpc.ServicerContext) -> l2cap_pb2.SendDataResponse:
        connection_value = request.connection.cookie.value
        output_stream = self.outgoing_connections.get(connection_value)
        if output_stream is None:
            output_stream = self._interleave_get_incoming_stream(connection_value)

        if output_stream:
            try:
                output_stream.send(request.data)
            except Exception as e:
                logging.error('Exception during writing to output stream: %s', e)
        else:
            logging.error('Output stream: %s not found for the connection_value: %s', output_stream, connection_value)

        return empty_pb2.Empty()

    async def ReceiveData(self, request: l2cap_pb2.ReceiveDataRequest,
                          context: grpc.ServicerContext) -> l2cap_pb2.ReceiveDataResponse:
        connection_value = request.connection.cookie.value
        input_stream = self.outgoing_connections.get(connection_value)
        if input_stream is None:
            input_stream = self._interleave_get_incoming_stream(connection_value)

        if input_stream:
            try:
                data = input_stream.recv(self.BUFFER_SIZE)
                if data:
                    return l2cap_pb2.ReceiveDataResponse(data=bytes(data))
            except Exception as e:
                logging.error('Exception during reading from input stream: %s', e)
        else:
            logging.error('Input stream: %s not found for the connection_value: %s', input_stream, connection_value)

        # Return an empty byte array.
        return l2cap_pb2.ReceiveDataResponse(data=b'')
