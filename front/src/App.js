// src/App.jsx
import React, { useEffect, useMemo, useState } from "react";
import {
  AppBar, Box, Button, Container, Divider, IconButton, Paper, Snackbar,
  Tab, Tabs, TextField, Toolbar, Typography, Alert, Stack, Chip
} from "@mui/material";
import RefreshIcon from "@mui/icons-material/Refresh";

const GATEWAY = "http://localhost:8080";
const CHAT    = "http://localhost:8082";
const MM      = "http://localhost:8081";
const AC      = "http://localhost:8085";
const STORE   = "http://localhost:8084";

export default function App() {
  const [tab, setTab] = useState(0);

  // Live streams
  const [chat, setChat] = useState([]);
  const [stats, setStats] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [inventory, setInventory] = useState([]);
  const [matches, setMatches] = useState([]);

  // UI
  const [snack, setSnack] = useState(null);

  // SSE subscribe once
  useEffect(() => {
    const es = new EventSource(`${GATEWAY}/stream`);
    es.onmessage = (e) => {
      try {
        const evt = JSON.parse(e.data);
        const t = evt.topic || (evt.payload && evt.payload.topic);
        const payload = evt.payload ?? evt;

        switch (t) {
          case "chat.messages":
            setChat((x) => [...x, payload].slice(-200));
            break;
          case "stats.updates":
            setStats((x) => [...x, payload].slice(-200));
            break;
          case "anticheat.alerts":
            setAlerts((x) => [...x, payload].slice(-200));
            break;
          case "inventory.granted":
            setInventory((x) => [...x, payload].slice(-200));
            break;
          case "match.created":
            setMatches((x) => [...x, { ...payload, status: "Created" }].slice(-200));
            break;
          case "match.finished":
            setMatches((x) => [...x].slice(-200));
            break;
          default:
            // ignore heartbeat/unknown
        }
      } catch {}
    };
    es.onerror = () => setSnack({ type: "error", msg: "SSE disconnected" });
    return () => es.close();
  }, []);

  // Snapshot loader
  const loadSnapshot = async () => {
    try {
      const r = await fetch(`${GATEWAY}/snapshot`);
      const j = await r.json();
      setChat(j.chat ?? []);
      setStats(j.stats ?? []);
      setAlerts(j.anticheat ?? []);
      setMatches(j.matchmaking ?? []);
      setInventory(getInventory(j.store, j.inventory));
      setSnack({ type: "success", msg: "Snapshot updated" });
    } catch (e) {
      setSnack({ type: "error", msg: "Snapshot failed" });
    }
  };

  useEffect(() => { loadSnapshot(); }, []);

  return (
    <Box sx={{ bgcolor: "background.default", minHeight: "100vh" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar>
          <Typography variant="h6" sx={{ flex: 1 }}>🎮 Gaming Platform Dashboard</Typography>
          <IconButton color="inherit" onClick={loadSnapshot} title="Refresh snapshot">
            <RefreshIcon />
          </IconButton>
        </Toolbar>
      </AppBar>

      <Container maxWidth="lg" sx={{ py: 3 }}>
        <Paper elevation={1} sx={{ borderRadius: 3, overflow: "hidden" }}>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable">
            <Tab label="Chat" />
            <Tab label="Stats" />
            <Tab label="AntiCheat" />
            <Tab label="Matchmaking" />
            <Tab label="Store" />
            <Tab label="Inventory" />
          </Tabs>
          <Divider />
          {tab === 0 && <ChatTab chat={chat} setSnack={setSnack} />}
          {tab === 1 && <StatsTab stats={stats} />}
          {tab === 2 && <AntiCheatTab alerts={alerts} setSnack={setSnack} />}
          {tab === 3 && <MatchmakingTab matches={matches} setSnack={setSnack} />}
          {tab === 4 && <StoreTab setSnack={setSnack} />}
          {tab === 5 && <InventoryTab inventory={inventory} />}
        </Paper>
      </Container>

      <Snackbar
        open={!!snack}
        autoHideDuration={2500}
        onClose={() => setSnack(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        {snack && <Alert severity={snack.type}>{snack.msg}</Alert>}
      </Snackbar>
    </Box>
  );
}

/* -------------------- Chat -------------------- */
function ChatTab({ chat, setSnack }) {
  const [room, setRoom] = useState("general");
  const [author, setAuthor] = useState("alice");
  const [text, setText] = useState("");

  const send = async (e) => {
    e.preventDefault();
    const url = `${CHAT}/send?room=${encodeURIComponent(room)}&author=${encodeURIComponent(author)}&text=${encodeURIComponent(text)}`;
    try {
      const r = await fetch(url);
      if (!r.ok) throw new Error();
      setSnack({ type: "success", msg: "Sent" });
      setText("");
    } catch {
      setSnack({ type: "error", msg: "Send failed" });
    }
  };

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" gutterBottom>💬 Send message</Typography>
      <Box component="form" onSubmit={send} sx={{ display: "grid", gap: 2, gridTemplateColumns: { sm: "1fr 1fr 2fr auto" }, alignItems: "center" }}>
        <TextField label="Room" size="small" value={room} onChange={e=>setRoom(e.target.value)} />
        <TextField label="Author" size="small" value={author} onChange={e=>setAuthor(e.target.value)} />
        <TextField label="Text" size="small" value={text} onChange={e=>setText(e.target.value)} />
        <Button variant="contained" type="submit">Send</Button>
      </Box>

      <Divider sx={{ my: 2 }} />
      <Typography variant="h6" gutterBottom>Recent</Typography>
      <Stack spacing={1}>
        {chat.slice().reverse().map((m, i) => (
          <Paper key={i} sx={{ p: 1.2, display: "flex", gap: 1, alignItems: "baseline" }}>
            <Chip size="small" label={m.room || "general"} />
            <Typography variant="body2"><b>{m.authorId}</b>: {m.text}</Typography>
          </Paper>
        ))}
        {chat.length === 0 && <Typography variant="body2" color="text.secondary">No messages yet.</Typography>}
      </Stack>
    </Box>
  );
}

/* -------------------- Stats -------------------- */
function StatsTab({ stats }) {

  const newStats = [...stats].sort((a,b) => a.mmr > b.mmr ? -1 : 1)

  console.log('m', newStats)
  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" gutterBottom>📊 Leaderboards</Typography>
      <Stack spacing={1}>
        {newStats.map((p, i) => (
          <Paper key={i} sx={{ p: 1.2, display: "flex", justifyContent: "space-between" }}>
            <Typography><b>{p.playerId.replace('+', '')}</b></Typography>
            <Typography variant="body2">MMR: {p.mmr} • wins: {p.wins}</Typography>
          </Paper>
        ))}
        {newStats.length === 0 && <Typography variant="body2" color="text.secondary">No stats yet.</Typography>}
      </Stack>
    </Box>
  );
}

/* -------------------- AntiCheat -------------------- */
function AntiCheatTab({ alerts, setSnack }) {
  const [playerId, setPlayerId] = useState("p1");
  const [matchId, setMatchId] = useState("m1");
  const [speed, setSpeed] = useState(15);

  const send = async (e) => {
    e.preventDefault();
    const url = `${AC}/telemetry?playerId=${encodeURIComponent(playerId)}&matchId=${encodeURIComponent(matchId)}&speed=${encodeURIComponent(speed)}`;
    try {
      const r = await fetch(url);
      if (!r.ok) throw new Error();
      setSnack({ type: "success", msg: "Telemetry sent" });
    } catch {
      setSnack({ type: "error", msg: "Send failed" });
    }
  };

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" gutterBottom>🚨 Send telemetry</Typography>
      <Box component="form" onSubmit={send} sx={{ display: "grid", gap: 2, gridTemplateColumns: { sm: "1fr 1fr 1fr auto" }, alignItems: "center" }}>
        <TextField label="Player" size="small" value={playerId} onChange={e=>setPlayerId(e.target.value)} />
        <TextField label="Match" size="small" value={matchId} onChange={e=>setMatchId(e.target.value)} />
        <TextField label="Speed" size="small" type="number" value={speed} onChange={e=>setSpeed(e.target.value)} />
        <Button variant="contained" type="submit">Send</Button>
      </Box>

      <Divider sx={{ my: 2 }} />
      <Typography variant="h6" gutterBottom>Recent alerts</Typography>
      <Stack spacing={1}>
        {alerts.slice().reverse().map((a, i) => (
          <Paper key={i} sx={{ p: 1.2, display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 1 }}>
            <Typography variant="body2"><b>{a.playerId}</b></Typography>
            <Typography variant="body2">speed</Typography>
            <Typography variant="body2" color="text.secondary">{a.score || a.speed}</Typography>
          </Paper>
        ))}
        {alerts.length === 0 && <Typography variant="body2" color="text.secondary">No alerts.</Typography>}
      </Stack>
    </Box>
  );
}

/* -------------------- Matchmaking -------------------- */
function MatchmakingTab({ matches, setSnack }) {
  const [player, setPlayer] = useState("p1");
  const [teamSize, setTeamSize] = useState(2);
  const [finish, setFinish] = useState({ matchId: "m1", playerId: "p1", kills: 3, win: true });

  const join = async () => {
    await call(`${MM}/join?player=${encodeURIComponent(player)}`, setSnack, "Joined", "Join failed");
  };
  const tick = async () => {
    await call(`${MM}/tick?teamSize=${encodeURIComponent(teamSize)}`, setSnack, "Ticked", "Tick failed");
  };
  const finishMatch = async () => {
    const q = new URLSearchParams(finish).toString();
    await call(`${MM}/finish?${q}`, setSnack, "Finished", "Finish failed");
  };

  console.log('matches',matches)

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" gutterBottom>🎯 Queue</Typography>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={2} alignItems="center">
        <TextField label="Player" size="small" value={player} onChange={e=>setPlayer(e.target.value)} />
        <Button variant="outlined" onClick={join}>Join</Button>
        <TextField label="Team size" size="small" type="number" value={teamSize} onChange={e=>setTeamSize(e.target.value)} />
        <Button variant="contained" onClick={tick}>Tick</Button>
      </Stack>

      <Divider sx={{ my: 2 }} />
      <Typography variant="h6" gutterBottom>🏁 Finish</Typography>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={2} alignItems="center">
        <TextField label="Match ID" size="small" value={finish.matchId} onChange={e=>setFinish({ ...finish, matchId:e.target.value })} />
        <TextField label="Player ID" size="small" value={finish.playerId} onChange={e=>setFinish({ ...finish, playerId:e.target.value })} />
        <TextField label="Kills" size="small" type="number" value={finish.kills} onChange={e=>setFinish({ ...finish, kills:e.target.value })} />
        <TextField label="Win" size="small" value={finish.win} onChange={e=>setFinish({ ...finish, win:e.target.value === "true" })} />
        <Button variant="contained" onClick={finishMatch}>Finish</Button>
      </Stack>

      <Divider sx={{ my: 2 }} />
      <Typography variant="h6" gutterBottom>Matches</Typography>
      <Stack spacing={1}>
        {matches.slice().reverse().map((m, i) => (
          <Paper key={i} sx={{ p: 1.2, display: "flex", justifyContent: "space-between" }}>
            <Box sx={{display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', width: '100%'}}>
              <Typography variant="body1"><b>Match ID</b></Typography>
              <Typography variant="body1"><b>Players</b></Typography>
              <Typography variant="body1"><b>Created At</b></Typography>
              <Typography variant="body2" sx={{cursor: 'pointer'}} onClick={() => setFinish((prev) => ({...prev, matchId: m.matchId}))}><b>{m.matchId || m.id || "?"}</b></Typography>
              <Typography variant="body2">{preparePlayerList(m.players).map((pl) => (
                <span style={{marginLeft: '5px', cursor: 'pointer'}} onClick={() => setFinish((prev) => ({...prev, playerId: pl}))}>{pl}</span>
              ))}</Typography>
              <Typography variant="body2"><b>{m.matchId || m.id || "?"}</b></Typography>
            </Box>
            <Chip size="small" label={m.status || 'Running'} />
          </Paper>
        ))}
        {matches.length === 0 && <Typography variant="body2" color="text.secondary">No matches yet.</Typography>}
      </Stack>
    </Box>
  );
}

/* -------------------- Store -------------------- */
function StoreTab({ setSnack }) {
  const [playerId, setPlayerId] = useState("p1");
  const [item, setItem] = useState("sword");
  const [amount, setAmount] = useState("9.99");
  const [currency, setCurrency] = useState("USD");

  const order = async (e) => {
    e.preventDefault();
    const q = new URLSearchParams({ playerId, item, amount, currency }).toString();
    await call(`${STORE}/order?${q}`, setSnack, "Order placed", "Order failed");
  };

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" gutterBottom>🛒 Create order</Typography>
      <Box component="form" onSubmit={order} sx={{ display: "grid", gap: 2, gridTemplateColumns: { sm: "1fr 1fr 1fr 1fr auto" }, alignItems: "center" }}>
        <TextField label="Player" size="small" value={playerId} onChange={e=>setPlayerId(e.target.value)} />
        <TextField label="Item" size="small" value={item} onChange={e=>setItem(e.target.value)} />
        <TextField label="Amount" size="small" value={amount} onChange={e=>setAmount(e.target.value)} />
        <TextField label="Currency" size="small" value={currency} onChange={e=>setCurrency(e.target.value)} />
        <Button variant="contained" type="submit">Buy</Button>
      </Box>
    </Box>
  );
}

/* -------------------- Inventory -------------------- */
function InventoryTab({ inventory }) {
  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h6" gutterBottom>🎁 Inventory grants</Typography>
      <Stack spacing={1}>
        {inventory.slice().reverse().map((g, i) => (
          <Paper key={i} sx={{ p: 1.2, display: "grid", gridTemplateColumns: "1fr 2fr 1fr", gap: 1 }}>
            <Typography variant="body2"><b>{g.playerId} {g.amount? 'bought for' : 'ordered'} {g.amount} {g.currency}</b></Typography>
            <Typography variant="body2">{g.item || g.itemName}</Typography>
            <Typography variant="body2" color="text.secondary">{g.created_at || "Processing"}</Typography>
          </Paper>
        ))}
        {inventory.length === 0 && <Typography variant="body2" color="text.secondary">No items yet.</Typography>}
      </Stack>
    </Box>
  );
}

/* -------------------- helpers -------------------- */
async function call(url, setSnack, okMsg, errMsg) {
  try {
    const r = await fetch(url);
    if (!r.ok) throw new Error(String(r.status));
    setSnack({ type: "success", msg: okMsg });
  } catch {
    setSnack({ type: "error", msg: errMsg });
  }
}


const preparePlayerList = (pl) => {
  if(Array.isArray(pl)){
    return pl.map(({id}) => id)
  }

  return pl.replace('[', '').replace(']', '').split(',')
}

const getInventory = (store, inv) => {
  return inv.map((invItem) => {
    const storeItem = store.find(s => s.order_id === invItem.order_id) ?? {};

    return {...invItem, ...storeItem}
  })
}