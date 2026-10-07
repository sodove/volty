import argparse, ipaddress, select, hashlib, base64, json, pathlib, re, secrets, socket, subprocess, time, urllib.request, urllib.error, uuid
parser=argparse.ArgumentParser(description='Owned disposable backend/PostgreSQL/SFU lifecycle and synthetic RTP probe')
parser.add_argument('--workspace',required=True)
parser.add_argument('--backend-image',required=True)
parser.add_argument('--sfu-image',required=True)
parser.add_argument('--sdk-sha256',required=True)
parser.add_argument('--restart-backend',action='store_true',help='Restart only this probe-owned backend while four media sessions are connected')
parser.add_argument('--observe-forwarding',action='store_true',help='Observe bound SFU downtracks and increasing sender packet counts on the private fixture debug listener')
args=parser.parse_args()
ROOT=pathlib.Path(args.workspace).resolve()
if not re.fullmatch(r'/tmp/volty-nearby-stack-[a-z0-9]+',str(ROOT)):raise RuntimeError('Owned workspace required')
BACKEND=args.backend_image
SFU=args.sfu_image
if not all(re.fullmatch(r'sha256:[a-f0-9]{64}',image) for image in (BACKEND,SFU)):raise RuntimeError('Exact candidate image IDs required')
SDK_HASH=args.sdk_sha256
if not re.fullmatch(r'[a-f0-9]{64}',SDK_HASH):raise RuntimeError('Expected SDK SHA256 required')
PG='postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73'
NET=ROOT.name.replace('volty-','',1)
owned=[]
sdk=None
def sdk_cmd(body):
    sdk.stdin.write(json.dumps(body)+"\n");sdk.stdin.flush()
    ready,_,_=select.select([sdk.stdout],[],[],20)
    require(bool(ready),"SDK command timed out")
    line=sdk.stdout.readline()
    require(bool(line),"SDK process ended")
    result=json.loads(line);require("error" not in result,"SDK command failed: "+result.get("error","unknown"))
    return result
def require(ok,msg):
    if not ok: raise RuntimeError(msg)
def docker(*args): return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.DEVNULL).strip()
def run(name,image,*args,command=(),network_mode=NET):
    cid=docker('run','-d','--name',name,'--label','volty.nearby.task='+NET,'--network',network_mode,*args,image,*command)
    require(re.fullmatch('[a-f0-9]{64}',cid),'container identity')
    identity=docker('inspect',cid,'--format','{{.Image}}')
    owned.append((cid,identity))
    require(identity==docker('image','inspect',image,'--format','{{.Id}}'),'image identity')
    return cid
def uid(): return str(uuid.uuid4())
def api(method,path,actor=None,body=None,expect=(200,)):
    headers={'Content-Type':'application/json'}
    if actor: headers['Authorization']='Bearer '+actor['token']
    req=urllib.request.Request('http://127.0.0.1:18089'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as response: code=response.status; raw=response.read()
    except urllib.error.HTTPError as error: code=error.code;raw=error.read()
    require(code in expect,'HTTP '+method+' '+path+' expected '+str(expect)+' got '+str(code)+' error='+str(json.loads(raw).get('code','unknown') if raw and code>=400 else 'withheld'))
    return json.loads(raw) if raw else {}
def register(i):
    r=api('POST','/v1/auth/register',body={'email':uid()+'@nearby.invalid','password':'Disposable-Only-'+secrets.token_hex(16),'displayName':'Fixture '+str(i)},expect=(201,))
    token=r['accessToken']; payload=json.loads(base64.urlsafe_b64decode(token.split('.')[1]+'==='))
    return {'token':token,'id':payload['sub']}
def check(name,ok): require(ok,name); print('check='+name+' passed',flush=True)
require(ROOT.exists() and ROOT.is_dir(),'owned scratch missing')
require({p.name for p in ROOT.iterdir()} <= {'probe.py','sdk-probe'},'Fresh workspace required')
require(hashlib.sha256((ROOT/'sdk-probe').read_bytes()).hexdigest()==SDK_HASH,'SDK binary identity')
for image in (BACKEND,SFU):require(docker('image','inspect',image,'--format','{{.Id}}')==image,'Candidate image identity')
require(not docker('ps','-aq','--filter','label=volty.nearby.task='+NET),'previous owned containers exist')
ports=[(18089,socket.SOCK_STREAM),(17881,socket.SOCK_STREAM),(17882,socket.SOCK_STREAM),(17883,socket.SOCK_DGRAM)]
if args.observe_forwarding:ports.append((17884,socket.SOCK_STREAM))
for port,kind in ports:
    with socket.socket(socket.AF_INET,kind) as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.bind(('127.0.0.1',port))
config='''port: 17881
bind_addresses: [0.0.0.0]
rtc:
  tcp_port: 17882
  udp_port: 17883
  node_ip: 127.0.0.1
  use_external_ip: false
  enable_loopback_candidate: true
keys:
  nearby-probe: local-nearby-probe-secret-not-production-20261003
logging:
  level: warn
'''
if args.observe_forwarding:config+='debug_handler:\n  port: 17884\n'
(ROOT/'sfu.yaml').write_text(config)
network=None
try:
    network=docker('network','create','--label','volty.nearby.task='+NET,NET)
    gateway=docker('network','inspect',network,'--format','{{(index .IPAM.Config 0).Gateway}}')
    address=ipaddress.ip_address(gateway)
    require(address.version==4 and address.is_private and not address.is_loopback,'isolated bridge gateway')
    (ROOT/'sfu.yaml').write_text(config.replace('bind_addresses: [0.0.0.0]','bind_addresses: [127.0.0.1, '+gateway+']'))
    db=run(NET+'-db',PG,'--network-alias','fixture-db','--memory=384m','--cpus=1','--tmpfs','/var/lib/postgresql/data:rw,size=192m','-e','POSTGRES_DB=volty','-e','POSTGRES_USER=volty','-e','POSTGRES_PASSWORD=fixture-db-password')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        p=subprocess.run(['docker','exec',db,'pg_isready','-U','volty','-d','volty'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if p.returncode==0:break
        time.sleep(.2)
    else:raise RuntimeError('fixture PostgreSQL startup failed')
    run(NET+'-sfu',SFU,'--memory=512m','--cpus=1','--read-only','--tmpfs','/tmp:rw,size=16m','--mount','type=bind,src='+str(ROOT/'sfu.yaml')+',dst=/etc/livekit.yaml,readonly','--entrypoint','/livekit-server',command=('--config','/etc/livekit.yaml'),network_mode='host')
    env={'PORT':'8080','VOLTY_DATABASE_URL':'jdbc:postgresql://fixture-db:5432/volty','VOLTY_DATABASE_USER':'volty','VOLTY_DATABASE_PASSWORD':'fixture-db-password','VOLTY_JWT_SECRET':'fixture-only-hmac-'+secrets.token_hex(32),'VOLTY_VOICE_PROVIDER':'livekit','LIVEKIT_URL':'ws://127.0.0.1:17881','LIVEKIT_API_KEY':'nearby-probe','LIVEKIT_API_SECRET':'local-nearby-probe-secret-not-production-20261003','VOLTY_PUBLIC_IP':'127.0.0.1','VOLTY_NEARBY_MODE':'voice_spaces','VOLTY_NEARBY_SFU_CONTROL_ORIGIN':'http://'+gateway+':17881','VOLTY_NEARBY_SINGLETON_ORIGIN':'false'}
    app_args=['--memory=768m','--cpus=1','--read-only','--tmpfs','/tmp:rw,size=64m','-p','127.0.0.1:18089:8080']
    for key,value in env.items(): app_args.extend(['-e',key+'='+value])
    app_container=run(NET+'-app',BACKEND,*app_args)
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        try:
            if api('GET','/health')['status']=='ok':break
        except (OSError,RuntimeError):pass
        time.sleep(.2)
    else:raise RuntimeError('fixture backend startup failed')
    check('immutable_stack_health',True)
    health=json.loads(docker('exec',app_container,'/usr/bin/curl','--fail','--silent','--max-time','5','http://127.0.0.1:8080/health'))
    check('candidate_health_executable',health['status']=='ok')
    users=[register(i) for i in range(4)];a,b,c,d=users
    public,private=uid(),uid()
    detail=api('PUT','/v2/nearby/communities/'+public,a,{'name':'Public '+public,'visibility':'PUBLIC'})
    channel=detail['channels'][0]['id']
    api('PUT','/v2/nearby/communities/'+private,a,{'name':'Private '+private,'visibility':'PRIVATE'})
    catalog=api('GET','/v2/nearby/communities/catalog',b)['items']
    check('public_catalog_excludes_private',public in [x['id'] for x in catalog] and private not in [x['id'] for x in catalog])
    api('GET','/v2/nearby/communities/'+private,b,expect=(404,))
    for user in users[1:]:api('POST','/v2/nearby/communities/'+public+'/join',user)
    check('four_public_memberships',all(public in [x['id'] for x in api('GET','/v2/nearby/communities',u)['items']] for u in users))
    invitation=uid();secret=base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
    api('PUT','/v2/nearby/communities/'+private+'/invitations/'+invitation,a,{'token':secret,'ttlMillis':900000,'maxUses':1})
    op=uid();receipt=api('POST','/v2/nearby/invitations/accept',b,{'operationId':op,'token':secret})
    retry=api('POST','/v2/nearby/invitations/accept',b,{'operationId':op,'token':secret})
    check('private_invitation_idempotent',receipt==retry and receipt['communityId']==private)
    api('POST','/v2/nearby/invitations/accept',c,{'operationId':uid(),'token':secret},expect=(404,))
    operation=uid();session=api('POST','/v2/nearby/operations/'+operation+'/join',b,{'destinationId':channel})
    check('backend_issues_connecting_credentials',session['phase']=='CONNECTING' and bool(session['credentials']['participantToken']))
    sid=session['sessionId']
    api('POST','/v2/nearby/sessions/'+sid+'/heartbeat',c,expect=(404,))
    disconnect=api('POST','/v2/nearby/sessions/'+sid+'/disconnect',b)
    check('disconnect_retains_membership',disconnect['phase'] in ('CANCELLED','DISCONNECTED','ENDED') and public in [x['id'] for x in api('GET','/v2/nearby/communities',b)['items']])
    roster=api('GET','/v2/nearby/channels/'+channel+'/roster',a)
    check('disconnected_session_absent',not any(x['sessionId']==sid for x in roster['peers']))
    probe=ROOT/'sdk-probe'
    require(hashlib.sha256(probe.read_bytes()).hexdigest()==SDK_HASH,'SDK binary identity')
    sdk=subprocess.Popen([str(probe)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
    sessions=[];identities=[]
    for user in users:
        joined=api('POST','/v2/nearby/operations/'+uid()+'/join',user,{'destinationId':channel})
        credential=joined['credentials']['participantToken']
        claims=json.loads(base64.urlsafe_b64decode(credential.split('.')[1]+'==='))
        identities.append(claims['sub']);sessions.append(joined['sessionId'])
        require(sdk_cmd({'command':'join','identity':claims['sub'],'token':credential})['joined'],'SDK join')
    deadline=time.monotonic()+12
    while time.monotonic()<deadline:
        phases=[api('POST','/v2/nearby/sessions/'+sid+'/heartbeat',user)['phase'] for user,sid in zip(users,sessions)]
        counts=sdk_cmd({'command':'counts'})
        if all(x=='CONNECTED' for x in phases) and all(counts[receiver][sender]>=10 for receiver in identities for sender in identities if receiver!=sender):break
        time.sleep(.2)
    else:raise RuntimeError('four actual connected duplex transports not earned')
    check('four_backend_credentials_actual_duplex',True)
    rosters=[api('GET','/v2/nearby/channels/'+channel+'/roster',u) for u in users]
    projections=[sorted((peer['actorId'],peer['sessionId'],peer['mediaPresence'],peer.get('participantSid'),peer['phase']) for peer in roster['peers']) for roster in rosters]
    check('four_observers_agree_active_roster',all(p==projections[0] for p in projections) and len(projections[0])==4 and all(p[2]=='CONNECTED' and p[3] and p[4]=='CONNECTED' for p in projections[0]))
    if args.observe_forwarding:
        def forwarding():
            # This listener shares the fixture's explicit loopback/private-gateway
            # bind addresses. Never query a production debug endpoint or emit its
            # raw room, participant, track or SSRC values.
            with urllib.request.urlopen('http://127.0.0.1:17884/debug/rooms',timeout=5) as response:
                rooms=json.load(response)
            matches=[room for room in rooms if set(room['Participants'])==set(identities)]
            require(len(matches)==1,'Expected exactly the owned four-participant room')
            peers=matches[0]['Participants']
            subscribers={peer['ID']:identity for identity,peer in peers.items()}
            counts={}
            for publisher,peer in peers.items():
                tracks=peer['UpTrackManager']['PublishedTracks']
                require(len(tracks)==1,'Expected one synthetic audio publication per publisher')
                track=next(iter(tracks.values()))
                require(track['Kind']=='AUDIO','Expected synthetic audio publication')
                for down in track['DownTracks']:
                    receiver=subscribers.get(down['SubscriberID'])
                    require(receiver is not None and receiver!=publisher,'Unexpected SFU subscriber')
                    require((receiver,publisher) not in counts,'Duplicate SFU forwarding direction')
                    require(down['BindState']==2 and down['Muted'] is False and down['PubMuted'] is False,'SFU downtrack is not bound and unmuted')
                    counts[(receiver,publisher)]=down['Stats'].get('PacketCount',0)
            require(len(counts)==12,'Expected all twelve SFU forwarding directions')
            return counts
        deadline=time.monotonic()+5
        while True:
            sent=forwarding()
            if all(count>=10 for count in sent.values()):break
            require(time.monotonic()<deadline,'SFU sender counters did not become available')
            time.sleep(.2)
        check('all_twelve_sfu_downtracks_bound_and_sending',True)
        time.sleep(1)
        later=forwarding()
        check('all_twelve_sfu_sender_counters_increase',all(later[pair]>count for pair,count in sent.items()))
    if args.restart_backend:
        identity=docker('inspect',app_container,'--format','{{.Id}}|{{.Image}}|{{index .Config.Labels "volty.nearby.task"}}')
        require(identity==app_container+'|'+BACKEND+'|'+NET,'restart ownership guard')
        before_restart=sdk_cmd({'command':'counts'})
        docker('restart','--time','2',app_container)
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            try:
                if api('GET','/health')['status']=='ok':break
            except (OSError,RuntimeError):pass
            time.sleep(.2)
        else:raise RuntimeError('Owned backend did not recover within lease window')
        check('owned_backend_restart_healthy',True)
        phases=[api('POST','/v2/nearby/sessions/'+sid+'/heartbeat',user)['phase'] for user,sid in zip(users,sessions)]
        check('durable_connected_sessions_survive_backend_restart',all(x=='CONNECTED' for x in phases))
        time.sleep(1)
        after_restart=sdk_cmd({'command':'counts'})
        check('all_pairs_continue_after_backend_restart',all(after_restart[x][y]>before_restart[x][y] for x in identities for y in identities if x!=y))
        rosters_after=[api('GET','/v2/nearby/channels/'+channel+'/roster',u)['peers'] for u in users]
        projections_after=[sorted((peer['actorId'],peer['sessionId'],peer['mediaPresence'],peer.get('participantSid'),peer['phase']) for peer in roster) for roster in rosters_after]
        check('original_sids_and_rosters_survive_backend_restart',all(x==projections[0] for x in projections_after))
    generation=uid();sharing='/v2/nearby/sharing/'+sessions[0]+'/'+generation
    consent=api('POST',sharing+'/start',a,{'mode':'LOCATION','ttl':'HALF_HOUR'})
    revoked=api('POST',sharing+'/revoke',a)
    check('gps_revoke_receipt',revoked['phase']=='REVOKED' and revoked['remainingMillis']==0)
    check('gps_revoke_preserves_voice',consent['phase']=='ACTIVE' and api('POST','/v2/nearby/sessions/'+sessions[0]+'/heartbeat',a)['phase']=='CONNECTED')
    api('POST','/v2/nearby/sessions/'+sessions[3]+'/disconnect',d)
    late=api('POST','/v2/nearby/sessions/'+sessions[3]+'/heartbeat',d)
    check('late_heartbeat_cannot_restore_disconnected_session',late['phase']=='CANCELLED')
    check('actual_connected_disconnect_retains_membership',public in [x['id'] for x in api('GET','/v2/nearby/communities',d)['items']])
    time.sleep(.3);before=sdk_cmd({'command':'counts'});time.sleep(1);after=sdk_cmd({'command':'counts'})
    dead=identities[3];alive=identities[:3]
    check('backend_disconnect_closes_media_without_client_stop',all(after[dead][x]==before[dead][x] and after[x][dead]==before[x][dead] for x in alive))
    check('survivors_remain_duplex',all(after[x][y]>before[x][y] for x in alive for y in alive if x!=y))
    remaining=[api('GET','/v2/nearby/channels/'+channel+'/roster',u)['peers'] for u in users]
    check('four_observers_agree_after_disconnect',all({p['actorId'] for p in roster}=={u['id'] for u in users[:3]} for roster in remaining))
    for user,sid in zip(users[:3],sessions[:3]):api('POST','/v2/nearby/sessions/'+sid+'/disconnect',user)
    sdk_cmd({'command':'close'});require(sdk.wait(timeout=10)==0,'SDK exit');sdk=None
    req=api('POST','/v1/friends/requests',a,{'userId':b['id']},expect=(201,))
    api('POST','/v1/friends/requests/'+req['friendshipId']+'/respond',b,{'accept':True})
    callid=uid();caller_operation=uid();call=api('POST','/v2/nearby/calls/'+callid+'/create',a,{'calleeId':b['id'],'idempotencyKey':uid(),'operationId':caller_operation})
    check('friend_direct_call_rings',call['call']['phase']=='RINGING' and any(x['callId']==callid for x in api('GET','/v2/nearby/calls/incoming',b)['calls']))
    accepted=api('POST','/v2/nearby/calls/'+callid+'/accept',b,{'operationId':uid(),'expectedRevision':call['call']['revision']})
    check('friend_direct_call_accept',accepted['call']['phase']=='ACCEPTED' and accepted['session']['phase']=='CONNECTING')
    caller=api('POST','/v2/nearby/calls/'+callid+'/resume',a,{'operationId':caller_operation})
    sdk=subprocess.Popen([str(probe)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
    call_sessions=[caller['session'],accepted['session']];call_identities=[]
    for state in call_sessions:
        credential=state['credentials']['participantToken']
        identity=json.loads(base64.urlsafe_b64decode(credential.split('.')[1]+'==='))['sub']
        call_identities.append(identity);sdk_cmd({'command':'join','identity':identity,'token':credential})
    deadline=time.monotonic()+12
    while time.monotonic()<deadline:
        phases=[api('POST','/v2/nearby/sessions/'+state['sessionId']+'/heartbeat',u)['phase'] for u,state in zip((a,b),call_sessions)]
        counts=sdk_cmd({'command':'counts'});x,y=call_identities
        if phases==['CONNECTED','CONNECTED'] and counts[x][y]>=10 and counts[y][x]>=10:break
        time.sleep(.2)
    else:raise RuntimeError('actual friend-call duplex transports not earned')
    check('friend_direct_call_actual_duplex',True)
    call_rosters=[api('GET','/v2/nearby/calls/'+callid+'/roster',u)['peers'] for u in (a,b)]
    expected={a['id'],b['id']}
    check('friend_call_roster_agreement',all(len(roster)==2 and {p['actorId'] for p in roster}==expected and all(p['mediaPresence']=='CONNECTED' and p['participantSid'] for p in roster) for roster in call_rosters))
    stopped=api('POST','/v2/nearby/calls/'+callid+'/stop',b)
    check('friend_direct_call_stop',stopped['call']['phase']=='ENDED')
    time.sleep(.3);before=sdk_cmd({'command':'counts'});time.sleep(1);after=sdk_cmd({'command':'counts'})
    check('friend_call_stop_closes_media_without_client_stop',after==before)
    sdk_cmd({'command':'close'});require(sdk.wait(timeout=10)==0,'SDK exit');sdk=None
    api('POST','/v2/nearby/communities/'+public+'/leave',d,{'operationId':uid()})
    check('membership_leave_separate',public not in [x['id'] for x in api('GET','/v2/nearby/communities',d)['items']])
    print('stack_http_matrix=passed connected_media=passed',flush=True)
finally:
    if sdk:
        sdk.kill();sdk.wait(timeout=10)
    for cid,image in reversed(owned):
        identity=docker('inspect',cid,'--format','{{.Id}}|{{.Image}}|{{index .Config.Labels "volty.nearby.task"}}')
        require(identity==cid+'|'+image+'|'+NET,'cleanup identity changed')
        try:
            logs=subprocess.check_output(['docker','logs',cid],text=True,stderr=subprocess.STDOUT)
        except subprocess.CalledProcessError:
            logs=''
        if not re.search(r'[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}',logs):
            (ROOT/(cid[:12]+'.log')).write_text(logs)
        docker('rm','-f',cid)
    if network:
        require(docker('network','inspect',network,'--format','{{index .Labels "volty.nearby.task"}}')==NET,'network ownership changed')
        docker('network','rm',network)
    print('owned_stack_removed=true',flush=True)
