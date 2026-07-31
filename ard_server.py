# In file: /opt/hornbillk_server_root/pi_server.py
import os
import json
import cv2
import boto3
import pygame
import threading
import time
import configparser
import re
import collections
import numpy as np
import subprocess
import requests
import uuid
import socket
import struct
from flask import Flask, Response, jsonify, request
from waitress import serve
from dotenv import load_dotenv
from functools import wraps 

# --- ‼️ CRITICAL VIDEO FIX: Force OpenCV to use TCP for RTSP ‼️ ---
os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"

# --- Configuration ---
CONFIG_FILE = 'config.ini'
IP_TABLE_FILE = 'ip_addresses.json'
TARGET_ANIMALS = ['bear', 'wild boar', 'elephant', 'leopard', 'tiger', 'monkey', 'peacock', 'cat', 'pig', 'dog']
AWS_TRIGGER_ANIMALS = TARGET_ANIMALS + ['cow']
DEFAULT_PI_ALIAS = "DEFAULT_PI_SERVER"
MAX_IP_TABLE_ENTRIES = 50
MODEL_DIR = 'models'
MODEL_CONFIG = os.path.join(MODEL_DIR, 'MobileNetSSD_deploy.prototxt')
MODEL_WEIGHTS = os.path.join(MODEL_DIR, 'MobileNetSSD_deploy.caffemodel')
MODEL_CLASSES = os.path.join(MODEL_DIR, 'mobilenet_ssd_classes.txt')
AWS_REKOGNITION_TRIGGER_CONFIDENCE = 30.0 
SMS_SEND_SCRIPT = './send_sms_from_pi.sh'
IP_CHECK_INTERVAL_SECONDS = 3600 
PI_IP_UPDATE_RECIPIENT_NUMBER = "+919495563335" # Default Admin Number
SMS_LOG_FILE = 'sms_log.json'

PHONE_NUMBER_REGEX = re.compile(r'^\+\d{10,15}$')

# --- Main Monitoring Class ---
class HeadlessMonitor:
    def __init__(self):
        self.sensitivity = 75
        self.interval = 5.0
        self.api_key = None
        self.admin_password = None
        self.cloudflare_tunnel_url = ""
        self.video_captures = [None] * 4
        self.is_monitoring = False
        self.rekognition_client = None
        self.alert_sounds = {}
        self.camera_urls = [""] * 4
        self.latest_frames = [None] * 4
        self.frame_locks = [threading.Lock() for _ in range(4)]
        self.previous_frames = [None] * 4
        self.alerts = collections.deque(maxlen=20)
        self.alerts_lock = threading.Lock()
        self.last_alert_frame = [None] * 4
        self.last_alert_frame_lock = [threading.Lock() for _ in range(4)]
        self.ip_addresses = collections.OrderedDict()
        self.ip_table_lock = threading.Lock()
        self.net = None
        self.class_names = []
        self.net_lock = threading.Lock()
        self.last_known_public_ipv4 = None
        self.last_known_public_ipv6 = None
        self.last_known_public_port = None
        self.ip_check_thread = None
        self.stop_ip_check_event = threading.Event()
        self.rekognition_lock = threading.Lock()
        self.last_detection_time = [0] * 4  
        self.last_alert_animal = [None] * 4 
        self.cooldown_period = 3600
        self.ip_alert_cooldown = 86400
        self.last_ip_alert_time = 0
        self.sms_log = {}
        self.sms_log_lock = threading.RLock()
        self.polling_thread = None
        self.stop_polling_event = threading.Event()
        self.SMS_DUPLICATE_LIMIT = 2
        self.SMS_DUPLICATE_WINDOW = 60
        self.sms_history = []


    def setup(self, api_key_from_config, config, admin_password_from_config):
        print("Flag: setup() - Start", flush=True)
        self.api_key = api_key_from_config
        if admin_password_from_config:
            self.admin_password = admin_password_from_config.strip()
        else:
            self.admin_password = None
            
        self.camera_urls = [config.get('Camera_URLs', f'camera_{i}_rtsp', fallback='') for i in range(4)]
        self.cloudflare_tunnel_url = config.get('Network', 'cloudflare_tunnel_url', fallback='').rstrip('/')
        self.sensitivity = config.getint('Monitoring', 'sensitivity', fallback=75)
        self.interval = config.getfloat('Monitoring', 'interval', fallback=5.0)
        
        self.cooldown_period = config.getint('Monitoring', 'cooldown_period', fallback=3600)
        sound_map = {animal: f"{animal.replace(' ', '_')}.mp3" for animal in TARGET_ANIMALS}
        sound_dir = config.get('Paths', 'sound_files_dir', fallback='.')
        if 'JOURNAL_STREAM' in os.environ or not os.isatty(0): 
            print("WARN: Non-interactive/Service mode, skipping Pygame audio.", flush=True)
        else:
            try: pygame.mixer.init(); print("Pygame mixer OK.", flush=True)
            except pygame.error: pass
            for animal, fname in sound_map.items():
                fpath=os.path.join(sound_dir, fname)
                if os.path.exists(fpath):
                    try: self.alert_sounds[animal]=pygame.mixer.Sound(fpath)
                    except pygame.error: pass

        print("Flag: setup() - Loading local ML model", flush=True)
        model_base = config.get('Paths', 'model_dir', fallback=MODEL_DIR)
        cfg=os.path.join(model_base,os.path.basename(MODEL_CONFIG))
        wts=os.path.join(model_base,os.path.basename(MODEL_WEIGHTS))
        cls=os.path.join(model_base,os.path.basename(MODEL_CLASSES))
        try:
            self.net = cv2.dnn.readNetFromCaffe(cfg, wts)
            with open(cls, 'r') as f: self.class_names = [ln.strip() for ln in f if ln.strip()]
            print(f"Loaded model: {len(self.class_names)} classes.", flush=True)
        except Exception as e: 
            print(f"FATAL: Load model failed: {e}", flush=True)
            return False
            
        print("Flag: setup() - Init AWS client", flush=True)
        aws_region = config.get('AWS', 'region', fallback='ap-south-1')
        aws_id=os.getenv("AWS_ACCESS_KEY_ID"); aws_sec=os.getenv("AWS_SECRET_ACCESS_KEY")
        if aws_id and aws_sec:
            try:
                self.rekognition_client=boto3.client('rekognition',aws_access_key_id=aws_id,aws_secret_access_key=aws_sec,region_name=aws_region)
                print("AWS client OK.", flush=True)
            except Exception: self.rekognition_client=None
        else: self.rekognition_client=None

        #self.load_ip_table()
        self.load_sms_log()
        self.start_ip_check_thread()
        self.start_sms_polling_thread()
        print("Flag: setup() - End (Setup OK)", flush=True)
        return True

    # --- Helper: Get All Recipients ---
    def get_all_recipient_numbers(self):
        """Collects unique phone numbers from config AND registered clients."""
        recipients = set()
        
        # 1. Add the Admin Number (from config/hardcoded)
        if PI_IP_UPDATE_RECIPIENT_NUMBER and PHONE_NUMBER_REGEX.match(PI_IP_UPDATE_RECIPIENT_NUMBER):
            recipients.add(PI_IP_UPDATE_RECIPIENT_NUMBER)
            
        # 2. Add numbers from Registered Clients
        with self.ip_table_lock:
            for alias, data in self.ip_addresses.items():
                if alias == 'server_state': continue
                num = data.get("phone_number")
                if num and PHONE_NUMBER_REGEX.match(num):
                    recipients.add(num)
        
        return list(recipients)

    # --- Core Logic ---

    def analyze_frame(self, frame, index):
        if frame is None or frame.size==0: return
        
        now=time.time()
        if now - self.last_detection_time[index] < self.cooldown_period:
            return

        if not self.net: return

        try:
            h,w=frame.shape[:2]; 
            blob=cv2.dnn.blobFromImage(cv2.resize(frame,(300,300)),0.007843,(300,300),127.5,swapRB=True)
            
            with self.net_lock: 
                self.net.setInput(blob)
                detections=self.net.forward()
            
            found=False; high_target=None; max_c=0.0
            SUSPICIOUS_ANIMALS = ['cat', 'dog', 'pig', 'cow']

            for i in range(detections.shape[2]):
                confidence=detections[0,0,i,2]
                
                if confidence > 0.1:
                    cid=int(detections[0,0,i,1])
                    if 0<=cid<len(self.class_names):
                        name=self.class_names[cid].lower()
                        
                        if name in SUSPICIOUS_ANIMALS:
                            if self.rekognition_client:
                                if confidence >= AWS_REKOGNITION_TRIGGER_CONFIDENCE / 100.0:
                                    if self.rekognition_lock.acquire(False):
                                        try:
                                            print(f"SUSPICIOUS: '{name}' ({confidence*100:.1f}%). Sending to AWS.", flush=True)
                                            threading.Thread(target=self.send_frame_to_rekognition,args=(frame.copy(),index,name,confidence),daemon=True).start()
                                            return 
                                        finally: self.rekognition_lock.release()
                                    else:
                                        continue 
                            else:
                                pass 

                        if name in TARGET_ANIMALS and name not in SUSPICIOUS_ANIMALS:
                             local_thresh = self.sensitivity/100.0
                             if confidence >= local_thresh:
                                 if confidence > max_c:
                                     max_c = confidence
                                     high_target = (name, confidence)
                                 found = True
            
            if found and high_target:
                name, conf = high_target
                print(f"ALARM: CONFIRMED {name} at {conf*100:.1f}%", flush=True)
                self.last_detection_time[index] = now
                self.last_alert_animal[index] = name
                threading.Thread(target=self._send_multi_client_alert, args=(frame.copy(), index, name, conf), daemon=True).start()
                
        except Exception as e: print(f"ERROR in analyze: {e}", flush=True)

    def send_frame_to_rekognition(self, frame, camera_index, trigger_label="", trigger_confidence=0.0):
        print(f"Flag: send_frame_to_rekognition() - Start Cam {camera_index+1}", flush=True)
        if not self.rekognition_client: 
            return
        try:
            ok, buf = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY),90])
            if not ok: return

            res = self.rekognition_client.detect_labels(Image={'Bytes':buf.tobytes()}, MaxLabels=20, MinConfidence=75)
            found = False; high_target = None; max_conf = 0.0
            for lbl in res.get('Labels', []):
                name = lbl['Name'].lower(); conf = lbl['Confidence']
                if name in TARGET_ANIMALS:
                    if conf > max_conf: max_conf = conf; high_target = (name, conf)
                    found = True
            
            if found and high_target:
                name, conf = high_target
                print(f"AWS ALARM: CONFIRMED {name} at {conf:.1f}%", flush=True)
                now = time.time()
                self.last_detection_time[camera_index] = now
                self.last_alert_animal[camera_index] = name
                threading.Thread(target=self._send_multi_client_alert, args=(frame.copy(), camera_index, name, conf/100.0), daemon=True).start()
        except Exception as e: 
            print(f"ERROR: Rekognition API call failed: {e}", flush=True)

    def _send_multi_client_alert(self, frame, camera_index, label_name, confidence):
        print(f"Flag: _send_multi_client_alert - Start for Cam {camera_index+1} ({label_name})", flush=True)
        
        with self.last_alert_frame_lock[camera_index]: 
            self.last_alert_frame[camera_index] = frame
        print(f"Flag: _send_multi_client_alert - Saved alert frame for Cam {camera_index+1}", flush=True)

        clean_url = self.cloudflare_tunnel_url.replace("https://", "").replace("http://", "")
        link = f"https://{clean_url}/alert_image/{camera_index}"
        
        # Define the clean text payload to prevent gateway drops[cite: 1]
        # Unified, clean format for all animal/wildlife alerts
        sms = f"ALERT! {label_name.upper()} on Cam {camera_index+1} ({confidence*100.0:.1f}%). View: https://{clean_url}/alert_image/{camera_index}"
        print(f"Flag: _send_multi_client_alert - Payload prepared: '{sms}'", flush=True)

        # --- MULTI-CLIENT SMS SENDING ---
        recipients = self.get_all_recipient_numbers()
        print(f"Triggering SMS for Cam {camera_index+1} to {len(recipients)} numbers: {recipients}", flush=True)

        for number in recipients:
            print(f"Flag: _send_multi_client_alert - Queueing SMS to {number}", flush=True)
            success = self.queue_sms_alert(number, sms)
            print(f"Flag: _send_multi_client_alert - Queue result for {number}: {success}", flush=True)
            
        print(f"Flag: _send_multi_client_alert - Completed dispatch for Cam {camera_index+1}", flush=True)

    def queue_sms_alert(self, to_number, message_content):
        self._clean_sms_history()
        # Check duplicate just for this specific message, not per number
        duplicate_count = sum(1 for content, timestamp in self.sms_history if content == message_content)
        
        if duplicate_count >= self.SMS_DUPLICATE_LIMIT: 
            print(f"ALERT BLOCKED: Anti-flood limit reached.", flush=True)
            return False 

        current_time = time.time()
        self.sms_history.append((message_content, current_time))
        
        with self.sms_log_lock:
            msg_id = str(uuid.uuid4())
            self.sms_log[msg_id] = {
                'to_number': to_number, 'message_content': message_content,
                'timestamp': current_time, 'last_sent': 0, 'retries': 0, 'acknowledged': False
            }
            self.save_sms_log()
        
        threading.Thread(target=self._send_single_message_from_log, args=(msg_id,), daemon=True).start()
        return True

    def start_sms_polling_thread(self):
        if self.polling_thread is None or not self.polling_thread.is_alive():
            self.stop_polling_event.clear()
            self.polling_thread = threading.Thread(target=self._sms_retry_loop, daemon=True)
            self.polling_thread.start()
            print("Flag: SMS Retry and ACK Polling Thread Started", flush=True)

    def _sms_retry_loop(self):
        """Runs in the background to check for ACKs and trigger retries."""
        while not self.stop_polling_event.is_set():
            current_time = time.time()
            
            # 1. Check for incoming SMS Acknowledgments
            self.check_gammu_inbox_for_acks()

            # 2. Check for timed-out messages that need a retry
            with self.sms_log_lock:
                log_changed = False
                for msg_id, data in list(self.sms_log.items()):
                    if not data.get('acknowledged', False):
                        time_since_last = current_time - data.get('last_sent', current_time)
                        
                        # Wait 45 seconds (middle of your 30-60s requirement)
                        if time_since_last >= 180.0:
                            if data.get('retries', 0) < 3: # Original send + 2 retries
                                print(f"RETRYING SMS to {data['to_number']} (Attempt {data['retries']})", flush=True)
                                # Launch the send command on a detached thread so we don't block the loop
                                threading.Thread(target=self._send_single_message_from_log, args=(msg_id,), daemon=True).start()
                            else:
                                print(f"SMS Retry limit reached for {data['to_number']}. Striking out.", flush=True)
                                data['acknowledged'] = True # Strike out to prevent infinite loops
                                log_changed = True
                
                if log_changed:
                    self.save_sms_log()
                    
            # Run this check every 10 seconds
            self.stop_polling_event.wait(10)

    def check_gammu_inbox_for_acks(self):
        """Reads the default Gammu spool folder for incoming Android app ACKs."""
        inbox_path = "/var/spool/gammu/inbox"
        if not os.path.exists(inbox_path): 
            return
            
        try:
            for filename in os.listdir(inbox_path):
                filepath = os.path.join(inbox_path, filename)
                if os.path.isfile(filepath):
                    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                        content = f.read()
                        # This matches the exact string your Kotlin app sends
                        if "ACK: ALERT_RECEIVED" in content:
                            print(f"SUCCESS: Acknowledgment received from phone!", flush=True)
                            
                            # Strike out all pending messages in the log
                            with self.sms_log_lock:
                                changed = False
                                for msg_id, data in self.sms_log.items():
                                    if not data.get('acknowledged', False):
                                        data['acknowledged'] = True
                                        changed = True
                                if changed:
                                    self.save_sms_log()
                                    
                    # Safely delete the incoming SMS file so it is not read twice
                    os.remove(filepath)
        except Exception as e:
            print(f"Error reading Gammu inbox for ACKs: {e}", flush=True)

    def _send_single_message_from_log(self, msg_id):
        with self.sms_log_lock:
            msg_data = self.sms_log.get(msg_id)
            if not msg_data: return False
            to_number = msg_data['to_number']
            message_content = msg_data['message_content']
            msg_data['last_sent'] = time.time()
            msg_data['retries'] = msg_data.get('retries', 0) + 1
            self.save_sms_log()

        SMS_SCRIPT_PATH = "/usr/bin/gammu-smsd-inject"; timeout=30
        try:
            command = [SMS_SCRIPT_PATH, 'TEXT', to_number, '-text', message_content]
            res=subprocess.run(command, capture_output=True, text=True, check=False, timeout=timeout)
            
            if res.returncode == 0:
                print(f"SUCCESS: SMS Sent ID {msg_id} to {to_number}", flush=True)
                return True
            else:
                print(f"ERROR: Gammu failed RC={res.returncode}. {res.stdout.strip()}", flush=True)
                return False 
        except Exception as e:
            print(f"FATAL: SMS subprocess error: {e}", flush=True)
            return False

    def _clean_sms_history(self):
        current_time = time.time()
        self.sms_history = [x for x in self.sms_history if current_time - x[1] < self.SMS_DUPLICATE_WINDOW]

    # --- Data Management ---
    def load_sms_log(self):
        print("Flag: load_sms_log()", flush=True); path=SMS_LOG_FILE; self.sms_log={}
        if os.path.exists(path):
            try:
                with self.sms_log_lock, open(path,'r') as f: loaded=json.load(f)
                if isinstance(loaded,dict): self.sms_log=loaded
            except Exception: pass
    def save_sms_log(self):
        path=SMS_LOG_FILE
        with self.sms_log_lock:
            try:
                tmp=path+".tmp";
                with open(tmp,'w') as f: json.dump(self.sms_log,f,indent=4)
                os.replace(tmp,path)
            except Exception as e: print(f"Error saving SMS log:{e}", flush=True)

    def load_ip_table(self):
        print("Flag: load_ip_table()", flush=True); path=IP_TABLE_FILE; ips=collections.OrderedDict(); state={}
        if os.path.exists(path):
            try:
                with self.ip_table_lock, open(path,'r') as f: loaded=json.load(f)
                if isinstance(loaded,dict):
                    self.ip_addresses=collections.OrderedDict([(k,v) for k,v in loaded.items() if k!='server_state'])
                    state=loaded.get('server_state',{})
                    self.last_ip_alert_time=state.get('last_ip_alert_time', 0)
                    self.last_known_public_ipv4=state.get('last_known_public_ipv4', None)
            except Exception: pass
        with self.ip_table_lock:
            if DEFAULT_PI_ALIAS not in self.ip_addresses:
                self.ip_addresses[DEFAULT_PI_ALIAS]={"ipv4":"0.0.0.0","ipv6":"::","phone_number":"N/A"}
                self.save_ip_table_atomic()
            self.ip_addresses.move_to_end(DEFAULT_PI_ALIAS,last=False)

    def save_ip_table_atomic(self):
        path=IP_TABLE_FILE
        with self.ip_table_lock: tmp_ips=collections.OrderedDict(self.ip_addresses.items());
        state={'last_ip_alert_time':self.last_ip_alert_time, 'last_known_public_ipv4': self.last_known_public_ipv4}
        tmp_ips['server_state']=state
        try:
            with open(path+".tmp",'w') as f: json.dump(tmp_ips,f,indent=4)
            os.replace(path+".tmp",path)
        except Exception: pass
    def save_ip_table(self): self.save_ip_table_atomic()
    
    def update_ip_entry(self, alias, ipv4, ipv6, phone_number):
        with self.ip_table_lock:
            # 🛠️ REMOVED THE DEFAULT_PI_ALIAS RESTRICTION COMPLETELY!
            
            # Enforce the maximum capacity rule before adding
            if len(self.ip_addresses) >= MAX_IP_TABLE_ENTRIES and alias not in self.ip_addresses:
                # Pop the oldest entry to make room
                self.ip_addresses.popitem(last=False)
                
            self.ip_addresses[alias] = {"ipv4": ipv4, "ipv6": ipv6, "phone_number": phone_number}
            self.save_ip_table_atomic()
            return True
            
    def get_ip_for_alias(self, alias):
        with self.ip_table_lock: return self.ip_addresses.get(alias,{}).copy()
    def get_all_ips(self):
        with self.ip_table_lock: return [(a,i.copy()) for a,i in self.ip_addresses.items()]
    def send_sms_alert(self, to_number, message_content):
        return self.queue_sms_alert(to_number, message_content)
    
    def stop_polling_thread(self):
        if self.polling_thread:
            self.stop_polling_event.set()
            self.polling_thread.join(timeout=5)
            self.polling_thread = None 

    def start_ip_check_thread(self):
        if self.ip_check_thread is None or not self.ip_check_thread.is_alive():
            self.stop_ip_check_event.clear()
            self.ip_check_thread = threading.Thread(target=self._ip_check_loop, daemon=True)
            self.ip_check_thread.start()

    def stop_ip_check_thread(self):
        if self.ip_check_thread:
            self.stop_ip_check_event.set()
            self.ip_check_thread.join(timeout=5)
            self.ip_check_thread = None

    def get_stun_ip_and_port(self, bind_port=5000):
        """Queries Google's STUN server to get the NAT-mapped public IP and Port."""
        print("Flag: STUN - Starting lookup process...", flush=True)
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(3.0)
            
            # ⚡ CRITICAL: You must bind to the exact local port your P2P service uses
            sock.bind(('', bind_port)) 
            print(f"Flag: STUN - Successfully bound to local port {bind_port}", flush=True)
            
            # Craft STUN Binding Request (RFC 5389)
            transaction_id = os.urandom(12)
            request = struct.pack('!H H I 12s', 0x0001, 0, 0x2112A442, transaction_id)
            print("Flag: STUN - Sending binding request to stun.l.google.com:19302", flush=True)
            sock.sendto(request, ('stun.l.google.com', 19302))
            
            data, _ = sock.recvfrom(1024)
            print(f"Flag: STUN - Received {len(data)} bytes response from server", flush=True)
            
            msg_type, msg_len, magic_cookie, _ = struct.unpack('!H H I 12s', data[:20])
            
            if msg_type == 0x0101: # Binding Response
                print("Flag: STUN - Valid Binding Response (0x0101) verified", flush=True)
                offset = 20
                while offset < 20 + msg_len:
                    attr_type, attr_len = struct.unpack('!H H', data[offset:offset+4])
                    offset += 4
                    if attr_type == 0x0020: # XOR-MAPPED-ADDRESS
                        print("Flag: STUN - Found XOR-MAPPED-ADDRESS attribute", flush=True)
                        _, port, ip = struct.unpack('!x B H 4s', data[offset:offset+8])
                        port ^= (magic_cookie >> 16)
                        ip = struct.pack('!I', struct.unpack('!I', ip)[0] ^ magic_cookie)
                        resolved_ip = socket.inet_ntoa(ip)
                        print(f"Flag: STUN Success -> Public IP: {resolved_ip}, Port: {port}", flush=True)
                        return resolved_ip, port
                    elif attr_type == 0x0001: # MAPPED-ADDRESS
                        print("Flag: STUN - Found MAPPED-ADDRESS attribute", flush=True)
                        _, port, ip = struct.unpack('!x B H 4s', data[offset:offset+8])
                        resolved_ip = socket.inet_ntoa(ip)
                        print(f"Flag: STUN Success -> Public IP: {resolved_ip}, Port: {port}", flush=True)
                        return resolved_ip, port
                    offset += attr_len
            else:
                print(f"Flag: STUN Error - Unexpected message type received: {hex(msg_type)}", flush=True)
        except Exception as e:
            print(f"STUN Error Exception: {e}", flush=True)
        finally:
            sock.close()
            print("Flag: STUN - Socket closed", flush=True)
        
        print("Flag: STUN Failed to resolve IP/Port", flush=True)
        return None, None

    def _ip_check_loop(self):
        initial_wait = 5; self.stop_ip_check_event.wait(initial_wait)
        while not self.stop_ip_check_event.is_set():
            try:
                current_ipv4 = None
                current_port = None
                
                try: 
                    # ⚡ Fire the STUN request instead of ipify
                    current_ipv4, current_port = self.get_stun_ip_and_port(bind_port=5000)
                except Exception: pass
                
                # Check if the IP OR the Port shifted
                ip_changed = (current_ipv4 and current_ipv4 != self.last_known_public_ipv4 and current_ipv4 != "0.0.0.0")
                
                # Fetch port state (make sure to add self.last_known_public_port = None to your __init__ !)
                last_port = getattr(self, 'last_known_public_port', None)
                port_changed = (current_port and current_port != last_port)
                
                changed = ip_changed or port_changed
                isfirst = (self.last_known_public_ipv4 is None and current_ipv4)
                now = time.time(); cool = (now - self.last_ip_alert_time) > self.ip_alert_cooldown
                
                # ⚡ FIXED THE SPAM BUG: Removed (is_log_empty and current_ipv4)
                send_required = changed or (isfirst and cool)
                
                if send_required:
                    # Initialize the SMS string cleanly without raw URLs to bypass carrier filters
                    sms = f"Hole Punch Active. IP: {current_ipv4} Port: {current_port}"
                    
                    # --- MULTI-CLIENT IP NOTIFICATION ---
                    recipients = self.get_all_recipient_numbers()
                    print(f"Sending STUN Alert to {len(recipients)} numbers: {sms}", flush=True)
                    for num in recipients:
                        self.queue_sms_alert(num, sms)
                    
                    self.last_ip_alert_time = now
                
                if current_ipv4: 
                    self.last_known_public_ipv4 = current_ipv4
                    self.last_known_public_port = current_port
                    self.save_ip_table_atomic()
            except Exception: pass
            finally:
                wait = IP_CHECK_INTERVAL_SECONDS; chunk = 60
                while wait > 0 and not self.stop_ip_check_event.is_set(): 
                    self.stop_ip_check_event.wait(min(wait, chunk)); wait -= chunk

    # --- Video & Monitoring Control ---
    def start_monitoring(self):
        if self.is_monitoring: return {"status": "Already monitoring.", "success": True}
        self.previous_frames = [None]*4; self.latest_frames = [None]*4
        self.is_monitoring = True
        cnt=0
        for i, url in enumerate(self.camera_urls):
            if url:
                threading.Thread(target=self.video_loop, args=(i, int(url) if url.isdigit() else url), name=f"CameraThread-{i}", daemon=True).start()
                cnt+=1
        print(f"Flag: start_monitoring() - Started {cnt} threads.", flush=True)
        return {"status": "Monitoring started.", "success": True}

    def stop_monitoring(self):
        self.is_monitoring = False; time.sleep(1.0)
        with self.ip_table_lock:
            for i, cap in enumerate(self.video_captures):
                if cap and cap.isOpened(): cap.release()
                self.video_captures[i] = None
        return {"success": True, "message": "Monitoring stopped."}

    def video_loop(self, index, capture_source):
        thread_name = threading.current_thread().name
        print(f"Flag: video_loop() - Start [{thread_name}]", flush=True)
        last_analysis_time = 0
        video_capture = None
        try:
            video_capture = cv2.VideoCapture(capture_source)
            if not video_capture.isOpened(): return
            with self.ip_table_lock: self.video_captures[index] = video_capture
            is_file = os.path.isfile(str(capture_source))
            timeout_start = time.time(); RECONNECT_TIMEOUT=10

            while self.is_monitoring:
                try:
                    ret, frame = video_capture.read()
                    current_time = time.time()
                    if ret and frame is not None:
                        # --- 1. FORCE SQUARE ASPECT RATIO (1:1) ---
                        h, w, _ = frame.shape
                        min_dim = min(h, w)
                        start_x = (w - min_dim) // 2
                        start_y = (h - min_dim) // 2
                        frame = frame[start_y:start_y+min_dim, start_x:start_x+min_dim]
                        # ------------------------------------------
                        
                        timeout_start = current_time
                        with self.frame_locks[index]: self.latest_frames[index] = frame.copy()
                        prev = self.previous_frames[index]
                        if prev is not None and (current_time - last_analysis_time >= self.interval):
                            changed = self.compare_frames(prev, frame)
                            last_analysis_time = current_time
                            if changed:
                                threading.Thread(target=self.analyze_frame, args=(frame.copy(), index), daemon=True).start()
                        self.previous_frames[index] = frame.copy()
                    else:
                        if is_file: 
                            video_capture.set(cv2.CAP_PROP_POS_FRAMES,0); time.sleep(1); continue
                        if current_time - timeout_start > RECONNECT_TIMEOUT:
                            if video_capture.isOpened(): video_capture.release()
                            video_capture = cv2.VideoCapture(capture_source)
                            if not video_capture.isOpened(): break
                            else: timeout_start = time.time(); time.sleep(2); continue
                        else: time.sleep(0.5)
                    time.sleep(0.01)
                except Exception: time.sleep(5)
        finally:
            if video_capture and video_capture.isOpened(): video_capture.release()

    def compare_frames(self, prev_frame, current_frame):
        if prev_frame is None or current_frame is None: return []
        if prev_frame.shape != current_frame.shape: return []
        try:
            prev_gray=cv2.cvtColor(prev_frame, cv2.COLOR_BGR2GRAY)
            current_gray=cv2.cvtColor(current_frame, cv2.COLOR_BGR2GRAY)
            prev_gray=cv2.GaussianBlur(prev_gray,(21,21),0)
            current_gray=cv2.GaussianBlur(current_gray,(21,21),0)
            diff=cv2.absdiff(prev_gray, current_gray)
            _, thresh=cv2.threshold(diff, self.sensitivity, 255, cv2.THRESH_BINARY)
            thresh=cv2.dilate(thresh, None, iterations=2)
            cnts, _=cv2.findContours(thresh.copy(), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            regions=[]
            for c in cnts:
                if cv2.contourArea(c) > 4000: regions.append(cv2.boundingRect(c))
            return regions
        except Exception: return []

    def stop_polling_thread(self): pass # No-op

# --- Flask App ---
monitor = HeadlessMonitor()
app = Flask(__name__)

# --- GLOBAL REQUEST LOGGER ---
@app.before_request
def log_request_info():
    print(f"DEBUG: Incoming Request -> {request.method} {request.path}", flush=True)

def require_api_key(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        incoming = request.headers.get('X-API-Key')
        if incoming != monitor.api_key: 
            print(f"DEBUG: API Key REJECTED. Got: '{incoming}', Expected: '{monitor.api_key}'", flush=True)
            return jsonify(success=False), 401
        return f(*args, **kwargs)
    return decorated_function

@app.route('/login', methods=['POST'])
def login():
    if request.json.get('api_key') == monitor.api_key: return jsonify(success=True)
    return jsonify(success=False), 401

@app.route('/register_alias', methods=['POST'])
@require_api_key
def register_alias():
    data = request.json
    if not data:
        return jsonify(success=False, message="Missing request data"), 400

    alias = data.get('alias')
    
    # 🛠️ FIX: Fallback to "N/A" if the app sends null, empty, or missing IP data
    ipv4 = data.get('ipv4') or "N/A"
    ipv6 = data.get('ipv6') or "N/A"
    phone = data.get('phone_number') or "N/A"
    
    if not alias: 
        return jsonify(success=False, message="Missing alias"), 400
    
    print(f"Registering client: {alias}, IPv4: {ipv4}, Phone: {phone}", flush=True)
    
    success = monitor.update_ip_entry(alias, ipv4, ipv6, phone)
    
    # If it still fails (e.g., if the app is sending the forbidden DEFAULT_PI_ALIAS name)
    if not success:
        return jsonify(success=False, message="Registry rejected alias"), 400
        
    return jsonify(success=True)

@app.route('/monitor/start', methods=['POST'])
@require_api_key
def start(): return jsonify(monitor.start_monitoring())

@app.route('/monitor/stop', methods=['POST'])
@require_api_key
def stop(): return jsonify(monitor.stop_monitoring())

@app.route('/image_on_demand/<int:index>')
@require_api_key
def image(index):
    if 0 <= index < 4:
        with monitor.frame_locks[index]: frame = monitor.latest_frames[index]
        if frame is not None:
            ret, jpeg = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
            if ret: return Response(jpeg.tobytes(), mimetype='image/jpeg')
    return "No image", 404

@app.route('/alert_image/<int:index>')
def alert_img(index):
    if 0 <= index < 4:
        with monitor.last_alert_frame_lock[index]: frame = monitor.last_alert_frame[index]
        if frame is not None:
            # Drop quality to 40% to make the file tiny for slow network speeds
            ret, jpeg = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 40])
            if ret: return Response(jpeg.tobytes(), mimetype='image/jpeg')
    return "No image", 404

# --- PASSWORD & SETTINGS ENDPOINTS ---
@app.route('/admin/authenticate', methods=['POST'])
@require_api_key
def admin_auth():
    data = request.json
    incoming_pass = data.get('password')
    print(f"DEBUG FLAG: /admin/authenticate received: '{incoming_pass}' | Stored: '{monitor.admin_password}'", flush=True)
    if incoming_pass == monitor.admin_password:
        return jsonify(success=True), 200
    else:
        return jsonify(success=False, message="Incorrect password"), 401

@app.route('/settings/sensitivity', methods=['POST'])
@require_api_key
def set_sens():
    data = request.json
    if data.get('password') != monitor.admin_password:
        return jsonify(success=False, message="Auth failed"), 401
    try:
        new_val = int(data.get('value'))
        monitor.sensitivity = max(0, min(100, new_val))
        config = configparser.ConfigParser(); config.read(CONFIG_FILE)
        config.set('Monitoring', 'sensitivity', str(monitor.sensitivity))
        with open(CONFIG_FILE, 'w') as f: config.write(f)
        print(f"Sensitivity updated to {monitor.sensitivity}", flush=True)
        return jsonify(success=True)
    except Exception as e:
        return jsonify(success=False, error=str(e)), 400

@app.route('/settings/interval', methods=['POST'])
@require_api_key
def set_int():
    data = request.json
    if data.get('password') != monitor.admin_password:
        return jsonify(success=False, message="Auth failed"), 401
    try:
        new_val = float(data.get('value'))
        monitor.interval = max(0.5, min(60.0, new_val))
        config = configparser.ConfigParser(); config.read(CONFIG_FILE)
        config.set('Monitoring', 'interval', str(monitor.interval))
        with open(CONFIG_FILE, 'w') as f: config.write(f)
        print(f"Interval updated to {monitor.interval}", flush=True)
        return jsonify(success=True)
    except Exception as e:
        return jsonify(success=False, error=str(e)), 400

@app.route('/settings/cooldown', methods=['POST'])
@require_api_key
def set_cooldown():
    data = request.json
    if data.get('password') != monitor.admin_password:
        return jsonify(success=False, message="Auth failed"), 401
    try:
        new_val = int(data.get('value'))
        # Bound the cooldown period to a safe spectrum (e.g., 5 seconds to 1 hour)
        monitor.cooldown_period = max(5, min(3600, new_val))
        
        # Persist the change atomically back to the local config disk layout
        config = configparser.ConfigParser()
        config.read(CONFIG_FILE)
        
        if not config.has_section('Monitoring'):
            config.add_section('Monitoring')
            
        config.set('Monitoring', 'cooldown_period', str(monitor.cooldown_period))
        with open(CONFIG_FILE, 'w') as f: 
            config.write(f)
            
        print(f"Cooldown period updated to {monitor.cooldown_period} seconds", flush=True)
        return jsonify(success=True)
    except Exception as e:
        return jsonify(success=False, error=str(e)), 400

if __name__ == '__main__':
    load_dotenv()
    config = configparser.ConfigParser(); config.read(CONFIG_FILE)
    if monitor.setup(config.get('Security', 'api_key'), config, config.get('Security', 'admin_password')):
        monitor.start_monitoring()
        try: 
            # Change host='::' to host='0.0.0.0'
            serve(app, host='0.0.0.0', port=5000, threads=8)
        except: 
            pass
    monitor.stop_monitoring()
