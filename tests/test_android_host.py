import asyncio
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
import android_host
import airtraffic_native
import carrier


class HostTest(unittest.IsolatedAsyncioTestCase):
    async def test_core_callback_precedes_final_asset(self):
        order=[]
        async def callback(): order.append('validated-and-journaled')
        async def session(udid, connection, assets, emit, authorize):
            self.assertEqual((udid,connection,assets),('device','USB',[('a','b')]))
            emit({'event':'before-final-asset'})
            self.assertTrue(await authorize())
            order.append('final-asset')
        with tempfile.TemporaryDirectory() as root, patch.object(airtraffic_native,'run_session',session):
            await android_host.host_session('device',[('a','b')],callback,Path(root))
            lines=Path(root,'host.jsonl').read_text().splitlines()
            result=json.loads(lines[-1].split(':',1)[1])
            self.assertTrue(result['ok'])
        self.assertEqual(order,['validated-and-journaled','final-asset'])

    async def test_failed_validation_never_authorizes_write(self):
        callback=AsyncMock(side_effect=RuntimeError('SIM changed'))
        async def session(udid, connection, assets, emit, authorize):
            await authorize()
            self.fail('Must not reach final asset')
        with tempfile.TemporaryDirectory() as root, patch.object(airtraffic_native,'run_session',session):
            with self.assertRaisesRegex(RuntimeError,'SIM changed'):
                await android_host.host_session('device',[],callback,Path(root))
            self.assertIn('"ok": false',Path(root,'host.jsonl').read_text())
        callback.assert_awaited_once()

    async def test_missing_or_duplicate_commit_pause_is_rejected(self):
        callback=AsyncMock()
        async def missing(*args): pass
        async def duplicate(udid, connection, assets, emit, authorize):
            await authorize(); await authorize()
        for session, expected in ((missing,'не достигнут'),(duplicate,'Повторная')):
            with tempfile.TemporaryDirectory() as root, patch.object(airtraffic_native,'run_session',session):
                with self.assertRaisesRegex(RuntimeError,expected):
                    await android_host.host_session('device',[],callback,Path(root))
        callback.assert_awaited_once()

    async def test_host_timeout_and_callback_timeout_remain_distinct(self):
        async def stuck(*args): await asyncio.sleep(10)
        async def callback_timeout(udid, connection, assets, emit, authorize): await authorize()
        callback=AsyncMock(side_effect=TimeoutError('AFC timeout'))
        with tempfile.TemporaryDirectory() as root, patch.object(carrier,'AIRTRAFFIC_SECONDS',0.01):
            with patch.object(airtraffic_native,'run_session',stuck):
                with self.assertRaisesRegex(RuntimeError,'Сбой AirTraffic'):
                    await android_host.host_session('device',[],callback,Path(root))
            with patch.object(airtraffic_native,'run_session',callback_timeout):
                with self.assertRaisesRegex(TimeoutError,'AFC timeout'):
                    await android_host.host_session('device',[],callback,Path(root))
