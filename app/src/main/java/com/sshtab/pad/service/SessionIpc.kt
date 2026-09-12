package com.sshtab.pad.service

object SessionIpc {
    const val MSG_SUBSCRIBE = 1
    const val MSG_UNSUBSCRIBE = 2
    const val MSG_CONNECT = 3
    const val MSG_DISCONNECT = 4
    const val MSG_WRITE = 5
    const val MSG_RESIZE = 6
    const val MSG_LIST = 7
    const val MSG_DOWNLOAD = 8
    const val MSG_UPLOAD = 9
    const val MSG_GET_LOG = 10
    const val MSG_ENSURE = 11

    const val MSG_OUTPUT = 100
    const val MSG_STATUS = 101
    const val MSG_FILES = 102
    const val MSG_LOG = 103
    const val MSG_REPLY = 104
    const val MSG_RESET = 105

    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_USER = "user"
    const val EXTRA_PASS = "pass"
    const val EXTRA_KIND = "kind"
    const val EXTRA_NAME = "name"
    const val EXTRA_PATH = "path"
    const val EXTRA_BYTES = "bytes"
    const val EXTRA_TEXT = "text"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_OK = "ok"
    const val EXTRA_ERROR = "error"
    const val EXTRA_COLS = "cols"
    const val EXTRA_ROWS = "rows"
}
