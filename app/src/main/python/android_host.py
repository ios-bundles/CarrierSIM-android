"""AirTraffic host in the app's event loop instead of a desktop subprocess."""
import asyncio
import json
from pathlib import Path


async def host_session(udid, assets, callback, run):
    import carrier
    from airtraffic_native import run_session
    paused = False
    run = Path(run)
    with (run/'host.jsonl').open('w', encoding='utf-8') as log:
        def emit(row):
            log.write('CARRIER_SWAP_JSON:' + json.dumps(row, ensure_ascii=False) + '\n')
            log.flush()
        async def authorize():
            nonlocal paused
            if paused:
                raise RuntimeError('Повторная пауза AirTraffic')
            # Core callback finishes AFC validation and journals before the final move.
            await callback()
            paused = True
            return True
        timeout = asyncio.timeout(carrier.AIRTRAFFIC_SECONDS)
        try:
            async with timeout:
                await run_session(udid, carrier.CONNECTION, assets, emit, authorize)
            if not paused:
                raise RuntimeError('Сбой AirTraffic: не достигнут этап подтверждения записи')
            emit({'ok': True, 'backend': 'android-native-atc'})
        except TimeoutError:
            if not timeout.expired():
                raise
            raise RuntimeError(f'Сбой AirTraffic: синхронизация не завершилась за {carrier.AIRTRAFFIC_SECONDS} с') from None
        except Exception as error:
            emit({'ok': False, 'error': str(error)})
            raise
