import express from 'express';
import cors from 'cors';

const app = express();
const port = Number(process.env.PORT || 4000);

app.use(cors());
app.use(express.json());

const state = {
  feederLimitMw: 92,
  datacenter: {
    id: 'dc-1',
    mw: 76,
    targetMw: 76,
    slopeMwPerMin: 0,
    remainingMin: 0
  },
  baselineMw: {
    utilityImport: 8,
    intertie: 2
  },
  coordination: {
    state: 'IDLE',
    recommended_action: 'Monitor AI load growth.'
  }
};

const severityForHeadroom = (headroomPct) => {
  if (headroomPct <= 5) return 'RED';
  if (headroomPct <= 15) return 'AMBER';
  return 'NORMAL';
};

const updateCoordination = () => {
  const total = getCurrentTotalMw();
  const limit = state.feederLimitMw;
  const rampSlope = state.datacenter.slopeMwPerMin;

  if (state.coordination.state === 'ACCEPTED' && Math.abs(rampSlope) < 0.0001) {
    state.coordination.state = 'ACTIVE';
    state.coordination.recommended_action = 'Ramp capped and actively coordinated.';
    return;
  }

  if (rampSlope > 0.01) {
    const timeToBreach = total >= limit ? 0 : (limit - total) / rampSlope;
    if (timeToBreach <= 30) {
      state.coordination.state = 'REQUESTED';
      state.coordination.recommended_action = `Accept coordinated ramp to cap at 88 MW (TTB ${Math.max(
        0,
        timeToBreach
      ).toFixed(1)} min).`;
      return;
    }
  }

  if (state.coordination.state !== 'ACTIVE') {
    state.coordination.state = 'IDLE';
    state.coordination.recommended_action = 'No immediate coordination action required.';
  }
};

const getCurrentTotalMw = () =>
  state.datacenter.mw + state.baselineMw.utilityImport + state.baselineMw.intertie;

const getOverview = () => {
  const totalMw = getCurrentTotalMw();
  const headroomMw = state.feederLimitMw - totalMw;
  const headroomPct = (headroomMw / state.feederLimitMw) * 100;
  const severity = severityForHeadroom(headroomPct);
  const slope = state.datacenter.slopeMwPerMin;
  const ttb = slope > 0.01 ? Math.max(0, headroomMw / slope) : null;

  const nodeStatus = severity === 'RED' ? 'RED' : severity === 'AMBER' ? 'AMBER' : 'NORMAL';

  const sld = {
    nodes: [
      { id: 'bus-1', type: 'bus', label: 'Utility Bus', status: nodeStatus, value: `${totalMw.toFixed(1)} MW` },
      { id: 'xfmr-1', type: 'transformer', label: 'Main Transformer', status: nodeStatus, value: '138/34.5 kV' },
      { id: 'brk-1', type: 'breaker', label: 'POI Breaker', status: nodeStatus, value: 'Closed' },
      { id: 'line-1', type: 'line', label: 'Feeder Line', status: nodeStatus, value: `${state.feederLimitMw} MW limit` },
      {
        id: 'poi-meter',
        type: 'poi_meter',
        label: 'POI Meter',
        status: nodeStatus,
        value: `${totalMw.toFixed(1)} / ${state.feederLimitMw} MW`
      },
      {
        id: 'dc-intertie',
        type: 'dc_intertie',
        label: 'DC Intertie',
        status: 'NORMAL',
        value: `${state.baselineMw.intertie.toFixed(1)} MW`
      },
      {
        id: 'ai-load',
        type: 'ai_load',
        label: 'AI Compute Load (dc-1)',
        status: nodeStatus,
        value: `${state.datacenter.mw.toFixed(1)} MW`
      }
    ],
    edges: [
      { from: 'bus-1', to: 'xfmr-1' },
      { from: 'xfmr-1', to: 'brk-1' },
      { from: 'brk-1', to: 'line-1' },
      { from: 'line-1', to: 'poi-meter' },
      { from: 'poi-meter', to: 'dc-intertie' },
      { from: 'dc-intertie', to: 'ai-load' }
    ],
    badges: [
      {
        on: 'poi-meter',
        text: `Headroom ${headroomPct.toFixed(1)}%${ttb !== null ? ` · TTB ${ttb.toFixed(1)} min` : ''}`,
        severity
      },
      {
        on: 'ai-load',
        text: `Ramp slope ${slope.toFixed(2)} MW/min`,
        severity: slope > 0.01 ? 'AMBER' : 'NORMAL'
      }
    ],
    coordination: state.coordination
  };

  const alerts = [];
  if (ttb !== null && ttb <= 30) {
    alerts.push({
      type: 'PREDICTED_BREACH',
      message: `Predicted feeder breach in ${ttb.toFixed(1)} minutes unless coordinated control is accepted.`,
      severity: 'RED',
      ttb_min: Number(ttb.toFixed(1))
    });
  }

  return {
    grid: {
      feeder_limit_mw: state.feederLimitMw,
      current_total_mw: Number(totalMw.toFixed(2)),
      headroom_pct: Number(headroomPct.toFixed(2)),
      severity
    },
    sld,
    alerts
  };
};

const stepSimulation = () => {
  const tickMin = 10 / 60;
  if (state.datacenter.remainingMin > 0 && Math.abs(state.datacenter.slopeMwPerMin) > 0.0001) {
    const delta = state.datacenter.slopeMwPerMin * tickMin;
    state.datacenter.mw = Number((state.datacenter.mw + delta).toFixed(3));
    state.datacenter.remainingMin = Math.max(0, state.datacenter.remainingMin - tickMin);

    if (state.datacenter.remainingMin === 0) {
      state.datacenter.slopeMwPerMin = 0;
      state.datacenter.mw = Number(state.datacenter.targetMw.toFixed(3));
    }
  }

  updateCoordination();
};

setInterval(stepSimulation, 10_000);

app.get('/api/overview', (_req, res) => {
  updateCoordination();
  res.json(getOverview());
});

app.post('/api/simulate/ramp', (req, res) => {
  const { datacenter_id: datacenterId, delta_mw: deltaMw, duration_min: durationMin } = req.body ?? {};
  if (datacenterId !== state.datacenter.id) {
    return res.status(404).json({ error: 'datacenter not found' });
  }
  if (typeof deltaMw !== 'number' || typeof durationMin !== 'number' || durationMin <= 0) {
    return res.status(400).json({ error: 'delta_mw and duration_min must be valid numbers' });
  }

  state.datacenter.targetMw = state.datacenter.mw + deltaMw;
  state.datacenter.remainingMin = durationMin;
  state.datacenter.slopeMwPerMin = deltaMw / durationMin;
  state.coordination.state = 'REQUESTED';
  state.coordination.recommended_action = 'Utility coordination requested for active AI ramp.';

  return res.json({
    ok: true,
    datacenter_id: state.datacenter.id,
    start_mw: Number(state.datacenter.mw.toFixed(2)),
    target_mw: Number(state.datacenter.targetMw.toFixed(2)),
    duration_min: durationMin,
    slope_mw_per_min: Number(state.datacenter.slopeMwPerMin.toFixed(3))
  });
});

app.post('/api/control/accept', (req, res) => {
  const { datacenter_id: datacenterId, target_mw: targetMw } = req.body ?? {};
  if (datacenterId !== state.datacenter.id) {
    return res.status(404).json({ error: 'datacenter not found' });
  }
  if (typeof targetMw !== 'number') {
    return res.status(400).json({ error: 'target_mw must be a number' });
  }

  state.datacenter.targetMw = targetMw;
  state.datacenter.mw = Math.min(state.datacenter.mw, targetMw);
  state.datacenter.slopeMwPerMin = 0;
  state.datacenter.remainingMin = 0;
  state.coordination.state = 'ACCEPTED';
  state.coordination.recommended_action = `Utility accepted cap at ${targetMw} MW.`;

  updateCoordination();

  return res.json({
    ok: true,
    datacenter_id: state.datacenter.id,
    applied_mw: Number(state.datacenter.mw.toFixed(2)),
    target_mw: targetMw,
    slope_mw_per_min: 0
  });
});

app.listen(port, () => {
  console.log(`Simulation API listening on port ${port}`);
});
