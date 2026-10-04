"""Apple AirTraffic transport for macOS and Windows.

Runs in CarrierSIM's disposable host subprocess. It does not import carrier:
the caller owns journals, backups and the CONTINUE acknowledgement protocol.
Events go through emit, matching the native Linux worker's process interface.
"""
import ctypes as C
import json
import os
import plistlib
import sys
import time
import uuid
from pathlib import Path


MAX_MESSAGE_BYTES = 64 * 1024 * 1024


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


# Windows handshake adapted from dhava-gautama/AirCard-Windows (MIT),
# src/airtraffic.rs at e0eadb0c88da55516f1c9f939fd4ce27560910d5.
# Public replayable host blob from yinyajiang/go-tunes, not device credentials.
# Windows SendSyncRequest generated an invalid Grappa in the verified setup.
# See LICENSE-AirCard.txt for the adaptation's license.
WINDOWS_LIBRARY_ID = '12.6.0.100'
WINDOWS_HOST_GRAPPA = bytes.fromhex(
    '0101111111111111111111111111111111110440bc2785e0dbf166361e07980a'
    '5ea48dba95b3b8ea265d62aefea51bb7b190e0b77126290ad39bb13fecc08c25'
    'a9561c517ac11e64905da029e61bdfd0ba22c313')

class AppleHost:
    def __init__(self, directories=()):
        self.handles = []
        self.pool = None
        if sys.platform == 'darwin':
            self.cf = C.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
            self.at = C.CDLL('/System/Library/PrivateFrameworks/AirTrafficHost.framework/AirTrafficHost')
            self.objc = C.CDLL('/usr/lib/libobjc.A.dylib')
            self.objc.objc_autoreleasePoolPush.restype = C.c_void_p
            self.objc.objc_autoreleasePoolPush.argtypes = []
            self.objc.objc_autoreleasePoolPop.argtypes = [C.c_void_p]
            self.objc.objc_autoreleasePoolPop.restype = None
            self.pool = self.objc.objc_autoreleasePoolPush()
        elif sys.platform == 'win32':
            require(C.sizeof(C.c_void_p) == 8, 'Нужен 64-битный Python и 64-битные компоненты Apple.')
            paths = [Path(p).resolve() for p in directories]
            for key in ('CommonProgramW6432', 'CommonProgramFiles'):
                base = os.environ.get(key)
                if base:
                    paths += [Path(base)/'Apple'/'Mobile Device Support',
                              Path(base)/'Apple'/'Apple Application Support']
            paths = list(dict.fromkeys(p for p in paths if p.is_dir()))
            for p in paths:
                self.handles.append(os.add_dll_directory(str(p)))
            def load(name):
                candidates = [p/name for p in paths if (p/name).is_file()]
                require(candidates, 'Не найдена ' + name + '. Установите iTunes x64 по ссылке '
                        'https://4pda.to/forum/index.php?showtopic=554020&st=3760#entry107393362 '
                        'или укажите папки библиотек через --apple-dir. Версия Microsoft Store может не подойти. '
                        'Если скачивание на 4PDA выдаёт ошибку 404, нужна регистрация, вход в аккаунт и некоторая активность на форуме.')
                return C.CDLL(str(candidates[0]), winmode=0x1100)
            self.cf = load('CoreFoundation.dll')
            self.at = load('AirTrafficHost.dll')
        else:
            raise RuntimeError('Поддерживаются macOS и Windows.')
        P, I, U = C.c_void_p, C.c_ssize_t, C.c_size_t
        def bind(lib, name, result, args):
            f = getattr(lib, name); f.restype = result; f.argtypes = args
        for name, result, args in [
            ('CFDataCreate', P, [P,P,I]), ('CFDataGetLength', I, [P]),
            ('CFDataGetBytePtr', P, [P]), ('CFRelease', None, [P]),
            ('CFPropertyListCreateWithData', P, [P,P,U,P,P]),
            ('CFPropertyListCreateData', P, [P,P,I,U,P])]:
            bind(self.cf, name, result, args)
        for name, result, args in [
            ('ATHostConnectionCreate', P, [P]), ('ATHostConnectionRelease', None, [P]),
            ('ATHostConnectionReadMessage', P, [P]),
            ('ATHostConnectionSendHostInfo', None, [P,P]),
            ('ATHostConnectionSendSyncRequest', None, [P,P,P,P]),
            ('ATHostConnectionSendMetadataSyncFinished', None, [P,P,P]),
            ('ATHostConnectionSendAssetCompleted', None, [P,P,P,P]),
            ('ATCFMessageGetName', P, [P]), ('ATCFMessageGetParam', P, [P,P])]:
            bind(self.at, name, result, args)
        if sys.platform == 'win32':
            for name, result, args in [
                ('ATHostConnectionCreateWithLibrary', P, [P,P,U]),
                ('ATHostConnectionGetCurrentSessionNumber', C.c_uint32, [P]),
                ('ATCFMessageCreate', P, [C.c_uint32,P,P]),
                # AT Boolean, not OSStatus: the Windows DLL returns only AL (1 on success).
                ('ATHostConnectionSendMessage', C.c_bool, [P,P]),
                ('ATHostConnectionSendPowerAssertion', C.c_int32, [P,P])]:
                bind(self.at, name, result, args)

    def encode(self, value):
        raw = plistlib.dumps(value, fmt=plistlib.FMT_BINARY)
        buf = C.create_string_buffer(raw)
        data = self.cf.CFDataCreate(None, buf, len(raw))
        require(data, 'CFDataCreate failed')
        try:
            result = self.cf.CFPropertyListCreateWithData(None, data, 0, None, None)
            require(result, 'CFPropertyListCreateWithData failed')
            return result
        finally:
            self.cf.CFRelease(data)

    def decode(self, value):
        require(value, 'Пустое сообщение Apple')
        data = self.cf.CFPropertyListCreateData(None, value, 200, 0, None)
        require(data, 'CFPropertyListCreateData failed')
        try:
            size = self.cf.CFDataGetLength(data)
            require(0 <= size <= MAX_MESSAGE_BYTES, 'Слишком большое сообщение Apple')
            return plistlib.loads(C.string_at(self.cf.CFDataGetBytePtr(data), size))
        finally:
            self.cf.CFRelease(data)

    def call(self, name, connection, *values):
        refs = []
        try:
            for v in values: refs.append(self.encode(v))
            return getattr(self.at, name)(connection, *refs)
        finally:
            for ref in refs: self.cf.CFRelease(ref)

    def close(self):
        if self.pool:
            self.objc.objc_autoreleasePoolPop(self.pool); self.pool = None


def run_worker(udid, assets, directories, emit):
    host = AppleHost(directories)
    connection = None
    try:
        sample = {'test': ['Book', 1, False]}
        ref = host.encode(sample)
        try: require(host.decode(ref) == sample, 'Ошибка обмена с CoreFoundation')
        finally: host.cf.CFRelease(ref)
        if udid is None:
            emit({'ok': True, 'deviceConnections': 0}); return
        ref = host.encode(udid)
        try:
            if sys.platform == 'win32':
                library = host.encode(WINDOWS_LIBRARY_ID)
                try: connection = host.at.ATHostConnectionCreateWithLibrary(library, ref, 0)
                finally: host.cf.CFRelease(library)
            else:
                connection = host.at.ATHostConnectionCreate(ref)
        finally: host.cf.CFRelease(ref)
        require(connection, 'Не удалось открыть AirTraffic. Закройте синхронизацию iTunes/Finder.')
        def until(wanted, limit):
            for _ in range(limit):
                msg = host.at.ATHostConnectionReadMessage(connection)
                if not msg: continue
                try:
                    name = host.decode(host.at.ATCFMessageGetName(msg))
                    try: body = json.dumps(host.decode(msg), ensure_ascii=False, default=str)[:4000]
                    except Exception as e: body = 'не прочитано: ' + (str(e).strip() or type(e).__name__)
                    emit({'event': 'message', 'name': name, 'body': body})
                    if name == wanted:
                        if name != 'AssetManifest': return True
                        key = host.encode('AssetManifest')
                        try: return host.decode(host.at.ATCFMessageGetParam(msg, key))
                        finally: host.cf.CFRelease(key)
                    require(name not in ('SyncFailed','SyncFinished'), 'Синхронизация закончилась преждевременно')
                finally: host.cf.CFRelease(msg)
            raise RuntimeError('Не получено сообщение ' + wanted)
        info = {'Type':'iTunes', 'Version':'13.7.0.161', 'SyncHostName':'CarrierSIM',
                'LibraryID':str(uuid.uuid4()), 'SyncedDataclasses':['Book'],
                'SyncedAssetTypes':['Book'], 'Wakeable':False}
        if sys.platform == 'darwin':
            import platform
            info['MacOSVersion'] = platform.mac_ver()[0]
        if sys.platform == 'win32':
            info = {'LibraryID': WINDOWS_LIBRARY_ID, 'Version': WINDOWS_LIBRARY_ID,
                    'SyncHostName': 'CarrierSIM', 'SyncedDataclasses': ['Book']}
            host.call('ATHostConnectionSendHostInfo', connection, info)
            until('SyncAllowed', 20)
            session = host.at.ATHostConnectionGetCurrentSessionNumber(connection)
            params = {'Dataclasses': ['Book'], 'DataclassAnchors': {'Book': '0'},
                      'HostInfo': {**info, 'Grappa': WINDOWS_HOST_GRAPPA}}
            command = host.encode('RequestingSync')
            try:
                value = host.encode(params)
                try: message = host.at.ATCFMessageCreate(session, command, value)
                finally: host.cf.CFRelease(value)
            finally: host.cf.CFRelease(command)
            require(message, 'ATCFMessageCreate(RequestingSync) failed')
            try:
                status = host.at.ATHostConnectionSendMessage(connection, message)
                emit({'event': 'requesting-sync', 'status': status})
                require(status, 'ATHostConnectionSendMessage(RequestingSync) не отправил сообщение')
            finally: host.cf.CFRelease(message)
        else:
            until('SyncAllowed', 8)
            host.call('ATHostConnectionSendHostInfo', connection, info)
            time.sleep(.2)
            host.call('ATHostConnectionSendSyncRequest', connection, ['Book'], {}, info)
        until('ReadyForSync', 12)
        # Empty assets allow a handshake probe without staging files or syncing metadata.
        if not assets:
            emit({'ok': True, 'probe': True}); return
        anchors = {'Book': '0'} if sys.platform == 'win32' else {}
        if sys.platform == 'win32':
            host.call('ATHostConnectionSendPowerAssertion', connection, True)
        host.call('ATHostConnectionSendMetadataSyncFinished', connection, {'Book':1}, anchors)
        manifest = until('AssetManifest', 20)
        require(isinstance(manifest,dict), 'Неверный манифест AirTraffic')
        books = [r for r in manifest.get('Book',[]) if isinstance(r,dict)]
        found = {r.get('AssetID') for r in books if r.get('IsDownload')}
        missing = [a for a,_ in assets if a not in found]
        if missing:
            # Keep what the phone actually answered: host.jsonl in the run folder.
            emit({'event':'manifest','dataclasses':sorted(map(str,manifest)),'expected':[a for a,_ in assets],
                    'book':[{k:str(v) for k,v in r.items()} for r in books[:50]]})
            raise RuntimeError(f'AirTraffic не подтвердил нужные объекты: iPhone вернул {len(books)} '
                               f'объект(ов) Book, не хватает {len(missing)} из {len(assets)}')
        for i,(identifier,destination) in enumerate(assets):
            if i == 2:
                emit({'event':'before-final-asset'})
                require(sys.stdin.readline().strip() == 'CONTINUE', 'Резервная копия не подтверждена')
            host.call('ATHostConnectionSendAssetCompleted', connection, identifier, 'Book', destination)
            if i+1 < len(assets): time.sleep(.9)
        time.sleep(6)
        emit({'ok':True})
    finally:
        if connection: host.at.ATHostConnectionRelease(connection)
        host.close()
