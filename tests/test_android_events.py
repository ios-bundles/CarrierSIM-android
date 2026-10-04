import asyncio
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock
import carrier
from android_events import sim_cards, report_cards, validate_options, ui_hooks


class Callback:
    def __init__(self):
        self.events = []
        self.stopped = False
    def event(self, value): self.events.append(json.loads(value))
    def getAction(self): return 'watch-call'
    def shouldStop(self): return self.stopped


class EventsTest(unittest.TestCase):
    def test_sim_events_do_not_expose_imsi_or_full_iccid(self):
        row = {'Slot':'kOne','MCC':'250','MNC':'01', 'InternationalMobileSubscriberIdentity':'250011234567890',
               'IntegratedCircuitCardIdentity':'12345678901234567890','CFBundleIdentifier':'com.apple.Vodafone_ro'}
        cards = sim_cards(carrier, [row], {'default':'Vodafone_hu.bundle','25001':'Vodafone_ro.bundle'})
        self.assertEqual(cards[0]['iccid'],'7890')
        self.assertEqual(cards[0]['plan'],'Vodafone_ro')
        self.assertNotIn(row['InternationalMobileSubscriberIdentity'],json.dumps(cards))
        self.assertNotIn(row['IntegratedCircuitCardIdentity'],json.dumps(cards))

    def test_foreign_sim_plan_distinguishes_all_from_explicit_selection(self):
        row = {'Slot':'kOne','MCC':'216','MNC':'70'}
        cards = sim_cards(carrier, [row], {'default':'Vodafone_hu.bundle'})
        self.assertEqual(cards[0]['plan'],'без изменений')
        self.assertEqual(cards[0]['explicitPlan'],'Vodafone_hu')

    def test_backup_path_cannot_escape_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            valid = root/'runs'/'backup'/'snapshot'
            valid.mkdir(parents=True);(valid/'journal.json').write_text('{}')
            self.assertEqual(validate_options(root,'restore-backup',{'backup':'backup'})[2],valid.parent)
            for value in ('../backup','',str(root)):
                with self.assertRaises(ValueError):validate_options(root,'restore-backup',{'backup':value})
            with self.assertRaises(ValueError):validate_options(root,'watch-call',{'seconds':1000})

    def test_manual_profile_requires_one_sim_and_valid_bundle(self):
        for slot in ('1', '2'):
            self.assertEqual(validate_options('.', 'install', {'sims': slot, 'bundle': 'Vodafone_hu'})[1], slot)
        for options in ({'sims': 'all', 'bundle': 'Vodafone_hu'},
                        {'sims': '1', 'bundle': '../profile'},
                        {'sims': '1', 'bundle': ''}, {'sims': '1', 'bundle': None}):
            with self.assertRaises(ValueError): validate_options('.', 'install', options)
        with self.assertRaises(ValueError):
            validate_options('.', 'restore', {'sims': '1', 'bundle': 'Vodafone_hu'})

    def test_report_uses_core_wording_including_unknown_values(self):
        text=carrier.diag_report({'kOne': {'rat':('kRatLTE',),'codec':('EVS',)}},[{'Slot':'kOne','MCC':'250','MNC':'01'}])
        cards=report_cards(text)
        self.assertEqual(cards[0]['title'],'SIM 1  ·  25001')
        self.assertIn({'label':'Сеть','value':'4G (LTE)'},cards[0]['items'])
        self.assertTrue(any('нет в журнале' in item['label'] for item in cards[0]['items']))


class CaptureTest(unittest.IsolatedAsyncioTestCase):
    def fake_core(self, stream):
        return SimpleNamespace(device_info=AsyncMock(return_value={}),carrier_rows=AsyncMock(return_value=[]),
            diag_collect=lambda state:None, diag_report=lambda state,rows:'',commcenter_stream=stream)

    async def test_stop_finishes_idle_stream_and_keeps_partial_log(self):
        closed=[]
        async def stream(device, seconds, path, on_entry, stop=None):
            path.write_text('received entry\n')
            try: await asyncio.sleep(60)
            finally: closed.append(True)
        core=self.fake_core(stream);callback=Callback()
        with tempfile.TemporaryDirectory() as folder:
            with ui_hooks(core,callback,{}):
                task=asyncio.create_task(core.commcenter_stream(None,60,Path(folder)/'commcenter.log',None))
                await asyncio.sleep(0.01);callback.stopped=True
                await asyncio.wait_for(task,1)
        self.assertEqual(closed,[True]);self.assertIs(core.commcenter_stream,stream)

    async def test_stop_before_data_is_error_and_restores_hooks(self):
        async def stream(*args, **kwargs): await asyncio.sleep(60)
        core=self.fake_core(stream);callback=Callback()
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError,'до получения данных'):
                with ui_hooks(core,callback,{}):
                    task=asyncio.create_task(core.commcenter_stream(None,60,Path(folder)/'log',None))
                    await asyncio.sleep(0.01);callback.stopped=True
                    await asyncio.wait_for(task,1)
        self.assertIs(core.commcenter_stream,stream)
