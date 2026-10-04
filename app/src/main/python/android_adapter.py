"""Android-specific entry point. CarrierSIM's core files remain byte-for-byte upstream."""
import asyncio
import contextlib
import io
import json
import os
import sys
import traceback
from pathlib import Path
from types import SimpleNamespace


class Output(io.TextIOBase):
    def __init__(self, callback):
        self.callback = callback
        self.pending = ''
    def write(self, text):
        self.pending += text
        while '\n' in self.pending:
            line, self.pending = self.pending.split('\n', 1)
            # Keep section spacing; terminal-width rules do not fit a phone.
            if line.strip() and set(line.strip()) == {'─'}:
                line = '─' * 24
            self.callback.emit(line)
        return len(text)
    def flush(self):
        if self.pending:
            self.callback.emit(self.pending)
            self.pending = ''


def run(root, address, serial, callback, action="status", options_json="{}"):
    root = Path(root)
    os.environ['HOME'] = str(root.parent)
    os.environ['USBMUXD_SOCKET_ADDRESS'] = address
    os.environ['CARRIERSIM_MENU'] = '1'
    output = Output(callback)
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        try:
            print(f'Python {sys.version.split()[0]} · платформа {sys.platform}')
            # Android blocks /proc/version; it is definitely not WSL.
            from pymobiledevice3.osu import os_utils
            os_utils.is_wsl = lambda: False
            from pymobiledevice3.lockdown import create_using_usbmux
            import carrier
            carrier.ROOT = root
            carrier.CONFIG = root / 'bundle.yaml'
            carrier.DIAG.clear()
            carrier.CONNECTION = 'USB'
            from android_host import host_session
            carrier.host_session = host_session
            carrier.linux_usbmuxd_status = lambda: 'Android USB-мост, без root'
            from android_events import validate_options, ui_hooks
            options = json.loads(options_json)
            seconds, sims, backup = validate_options(root, action, options)
            args = SimpleNamespace(udid=serial, wait_seconds=120, recover=Path('AUTO') if action=='recover' else None,
                status=action=='status', sims=sims, bundles=carrier.load_bundle_config(root/'bundle.yaml'),
                restore=action=='restore', restore_backup=backup, runs=root/'runs', attempts=1 if action=='status' else 3,
                trigger=None, bundle=None, diagnose=action=='diagnose', report=action=='report', watch_call=action=='watch-call', seconds=seconds)
            if options.get('bundle'):
                args.bundle = options['bundle'].removesuffix('.bundle') + '.bundle'
                args.bundles = {'default': args.bundle}
            # Verifies all original archive contents before connecting.
            assets = carrier.load_assets()
            print(f'CarrierSIM {carrier.VERSION}: оригинальное Python-ядро загружено, архив проверен.')
            args.runs.mkdir(parents=True, exist_ok=True)
            carrier.DIAG['args'] = args
            # Import the actual file/installation services before any device write.
            from pymobiledevice3.services.afc import AfcService
            from pymobiledevice3.services.installation_proxy import InstallationProxyService
            with carrier.operation_lock(args.runs), ui_hooks(carrier, callback, args.bundles):
                result = asyncio.run(carrier.execute_with_retry(args, assets))
                if action == 'status':
                    catalog = carrier.load_catalog(args.runs)
                    if catalog:
                        names = sorted({name.removesuffix('.bundle') for name in catalog['bundles']})
                        callback.event(json.dumps({'type': 'profiles', 'items': names}, ensure_ascii=False))
            if action in ('diagnose', 'watch-call', 'report'):
                reports = sorted(args.runs.glob('*/report.txt'), key=lambda p: p.stat().st_mtime)
                if reports:
                    report = reports[-1]
                    text = report.read_text(encoding='utf-8')
                    if action == 'report':
                        answers = options.get('answers')
                        if isinstance(answers, dict):
                            info = carrier.DIAG.get('info', {})
                            text = carrier.report_text(info, info.get('carriers', []),
                                text.split('Журнал CommCenter:', 1)[-1],
                                {slot: [carrier.report_answer(str(a)) for a in values[:len(carrier.REPORT_QUESTIONS)]]
                                 for slot, values in answers.items() if isinstance(values, list)},
                                carrier.mask_log(str(options.get('region', '')))[:60])
                            report.write_text(text + '\n', encoding='utf-8')
                    callback.event(json.dumps({'type':'report', 'text':text, 'file':str(report.relative_to(args.runs))}, ensure_ascii=False))
            if action != 'status':
                if result not in (None, 0):
                    raise RuntimeError(f'Действие завершилось с кодом {result}')
                print('Действие завершено.')
                return True
            print('Статус и план прочитаны. Запись профилей не выполнялась.')
            from airtraffic_native import run_session
            async def probe():
                async def authorize():
                    raise RuntimeError('Запись запрещена в режиме проверки')
                def event(value):
                    name = value.get('event')
                    if name and name != 'message':
                        print(f'AirTraffic: {name}')
                await run_session(serial, 'USB', [], event, authorize, probe=True)
            async def check_afc():
                device = await create_using_usbmux(serial=serial, autopair=False)
                try:
                    async with AfcService(device) as afc:
                        await afc.listdir('/')
                    print('AFC: чтение файлов доступно без root.')
                finally:
                    await device.close()
            asyncio.run(check_afc())
            print('Проверяю защищённый сеанс AirTraffic без записи…')
            asyncio.run(probe())
            print('AirTraffic: handshake прошёл без root. Установка ещё не запускалась.')
            return True
        except Exception as error:
            print(f'Ошибка: {type(error).__name__}: {error}')
            callback.event(json.dumps({'type':'error', 'message':str(error)}, ensure_ascii=False))
            traceback.print_exc()
            return False
        finally:
            output.flush()
