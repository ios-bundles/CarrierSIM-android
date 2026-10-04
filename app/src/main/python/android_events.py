"""Structured UI events; upstream carrier.py is deliberately unchanged."""
import asyncio
import contextlib
import json
import re
import time
from pathlib import Path

READ_ACTIONS = {'status', 'diagnose', 'watch-call', 'report'}
ACTIONS = READ_ACTIONS | {'install', 'restore', 'recover', 'restore-backup'}


def validate_options(root, action, options):
    if action not in ACTIONS:
        raise ValueError('Неизвестное действие')
    seconds = int(options.get('seconds', 90))
    if not 5 <= seconds <= 600:
        raise ValueError('Длительность должна быть от 5 до 600 секунд')
    sims = str(options.get('sims', 'all'))
    if sims not in ('all', '1', '2'):
        raise ValueError('Неизвестный выбор SIM')
    if 'bundle' in options:
        bundle = options['bundle']
        if action != 'install' or sims == 'all' or not isinstance(bundle, str) or not re.fullmatch(r'[A-Za-z0-9_]+(?:\.bundle)?', bundle):
            raise ValueError('Для ручного профиля выберите одну SIM и корректное имя пакета')
    backup = None
    if action == 'restore-backup':
        runs = (Path(root) / 'runs').resolve()
        backup = (runs / str(options.get('backup', ''))).resolve()
        if backup.parent != runs or not (backup / 'snapshot' / 'journal.json').is_file():
            raise ValueError('Копия не найдена в журналах приложения')
    return seconds, sims, backup


def sim_cards(carrier, rows, config, top=None):
    top = top or {}
    result = []
    for row in rows:
        if row.get('Slot') not in carrier.SLOT_NAMES:
            continue
        plmn = f"{row.get('MCC', '')}-{row.get('MNC', '')}"
        iccid = str(row.get('IntegratedCircuitCardIdentity', ''))
        bundle = carrier.bundle_for(str(row.get('MCC', '')) + str(row.get('MNC', '')), config, False)
        embedded = top.get('SIM1IsEmbedded' if row['Slot'] == 'kOne' else 'SIM2IsEmbedded')
        kind = 'eSIM' if embedded is True or (embedded is None and 'Absent' in str(top.get('SIMTrayStatus', ''))) else 'SIM' if embedded is False else 'unknown'
        result.append({'kind': kind, 'slot': row['Slot'], 'title': carrier.SLOT_NAMES[row['Slot']],
                       'operator': carrier.OPERATORS.get(plmn, plmn), 'plmn': plmn,
                       'iccid': iccid[-4:] if len(iccid) >= 4 else '',
                       'current': str(row.get('CFBundleIdentifier', '')).removeprefix('com.apple.') or 'неизвестно',
                       'plan': bundle.removesuffix('.bundle') if bundle else 'без изменений',
                       'explicitPlan': carrier.bundle_for(str(row.get('MCC', '')) + str(row.get('MNC', '')), config, True).removesuffix('.bundle')})
    return result


def report_cards(text):
    """Split core's human-readable report for display, without interpreting absent data."""
    cards = []
    for line in text.splitlines():
        if line.startswith('  ') and not line.startswith('    ') and line.strip():
            cards.append({'title': line.strip(), 'items': []})
        elif line.startswith('    ') and cards:
            parts = line.strip().split('  ', 1)
            cards[-1]['items'].append({'label': parts[0], 'value': parts[1].strip() if len(parts) > 1 else ''})
    return cards


@contextlib.contextmanager
def ui_hooks(carrier, callback, config):
    original = {name: getattr(carrier, name) for name in
                ('device_info', 'carrier_rows', 'diag_collect', 'diag_report', 'commcenter_stream')}
    rows = []
    last = 0.0
    def event(kind, **fields):
        callback.event(json.dumps({'type': kind, **fields}, ensure_ascii=False))
    async def device_info(device):
        info = await original['device_info'](device)
        event('device', name=carrier.MODELS.get(info.get('ProductType'), {}).get('name', info.get('ProductType', 'iPhone')),
              version=info.get('ProductVersion', ''), build=info.get('BuildVersion', ''),
              family=info.get('DeviceClass', 'iPhone'), cellular=info.get('TelephonyCapability') is not False)
        return info
    async def carrier_rows(device):
        nonlocal rows
        rows = await original['carrier_rows'](device)
        try:
            top = await device.get_value() or {}
        except Exception:
            top = {}
        event('sims', items=sim_cards(carrier, rows, config, top))
        return rows
    def diag_report(state, report_rows):
        text = original['diag_report'](state, report_rows)
        event('diagnostics', items=report_cards(text))
        return text
    def diag_collect(state):
        collect = original['diag_collect'](state)
        def feed(entry, message):
            nonlocal last
            result = collect(entry, message)
            now = time.monotonic()
            if now - last >= 1:
                diag_report(state, rows)
                last = now
            return result
        feed.reset = collect.reset
        return feed
    async def commcenter_stream(device, seconds, log_path, on_entry, stop=None):
        event('phase', title='Сбор журнала CommCenter', detail='Сделайте тестовый звонок.' if callback.getAction() == 'watch-call' else
              'Включите авиарежим на 10 секунд, затем выключите. Wi-Fi оставьте включённым.', seconds=seconds)
        # Waking an idle stream ends the capture too, even if iOS emits no new entry.
        async def collect():
            return await original['commcenter_stream'](device, seconds, log_path, on_entry,
                stop=lambda: callback.shouldStop() or bool(stop and stop()))
        task = asyncio.create_task(collect())
        try:
            while not task.done():
                if callback.shouldStop():
                    task.cancel()
                    try:
                        await task
                    except asyncio.CancelledError:
                        if not log_path.is_file() or not log_path.stat().st_size:
                            raise RuntimeError('Сбор завершён до получения данных. Повторите проверку.') from None
                    return
                await asyncio.wait({task}, timeout=0.25)
            return await task
        finally:
            if not task.done():
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
    carrier.device_info, carrier.carrier_rows = device_info, carrier_rows
    carrier.diag_collect, carrier.diag_report = diag_collect, diag_report
    carrier.commcenter_stream = commcenter_stream
    try:
        yield event
    finally:
        for name, function in original.items():
            setattr(carrier, name, function)
