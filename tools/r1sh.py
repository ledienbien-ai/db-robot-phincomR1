import asyncio, json, sys, websockets
async def run(cmd, t=8):
    async with websockets.connect("ws://10.25.113.209:8080", subprotocols=["v1"], open_timeout=6) as ws:
        await ws.send(json.dumps({"type":"shell","type_id":"myshell","shell":cmd}))
        end=asyncio.get_event_loop().time()+t; out=[]
        while asyncio.get_event_loop().time()<end:
            try: m=await asyncio.wait_for(ws.recv(), end-asyncio.get_event_loop().time())
            except asyncio.TimeoutError: break
            try: out.append(json.loads(m).get("data",""))
            except: out.append(str(m))
        return "\n".join(out)
print(asyncio.run(run(sys.argv[1])))
