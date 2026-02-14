import { useEffect, useMemo, useState } from 'react';

type Status = 'NORMAL' | 'AMBER' | 'RED' | 'UNKNOWN';
type Severity = 'NORMAL' | 'AMBER' | 'RED';
type CoordinationState = 'IDLE' | 'REQUESTED' | 'ACCEPTED' | 'ACTIVE';

type Node = { id: string; type: string; label: string; status: Status; value: string };
type Edge = { from: string; to: string };
type Badge = { on: string; text: string; severity: Severity };
type Coordination = { state: CoordinationState; recommended_action: string };

type Overview = {
  grid: {
    feeder_limit_mw: number;
    current_total_mw: number;
    headroom_pct: number;
    severity: Severity;
  };
  sld: {
    nodes: Node[];
    edges: Edge[];
    badges: Badge[];
    coordination: Coordination;
  };
  alerts: Array<{ type: string; message: string; severity: Severity; ttb_min: number }>;
};

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:4000';

const positions: Record<string, { x: number; y: number }> = {
  'bus-1': { x: 70, y: 180 },
  'xfmr-1': { x: 210, y: 180 },
  'brk-1': { x: 350, y: 180 },
  'line-1': { x: 490, y: 180 },
  'poi-meter': { x: 630, y: 180 },
  'dc-intertie': { x: 770, y: 180 },
  'ai-load': { x: 910, y: 180 }
};

function App() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const selectedNode = useMemo(
    () => overview?.sld.nodes.find((node) => node.id === selectedId) ?? null,
    [overview, selectedId]
  );

  const fetchOverview = async () => {
    try {
      const res = await fetch(`${API_BASE_URL}/api/overview`);
      if (!res.ok) throw new Error(`Failed to load overview (${res.status})`);
      setOverview((await res.json()) as Overview);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown API error');
    }
  };

  useEffect(() => {
    fetchOverview();
    const id = setInterval(fetchOverview, 3000);
    return () => clearInterval(id);
  }, []);

  const postAction = async (path: string, payload: object) => {
    setBusyAction(path);
    try {
      const res = await fetch(`${API_BASE_URL}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (!res.ok) throw new Error(await res.text());
      await fetchOverview();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Action failed');
    } finally {
      setBusyAction(null);
    }
  };

  return (
    <div className="app-shell">
      <header>
        <h1>pFab Utility POI Demo (US)</h1>
        <p>Operational single-line view for AI load coordination at the Point of Interconnection.</p>
      </header>

      <section className="kpi-row">
        <div className="kpi">
          <span>Feeder Limit</span>
          <strong>{overview?.grid.feeder_limit_mw ?? '--'} MW</strong>
        </div>
        <div className="kpi">
          <span>Current Total</span>
          <strong>{overview?.grid.current_total_mw ?? '--'} MW</strong>
        </div>
        <div className="kpi">
          <span>Headroom</span>
          <strong>{overview ? `${overview.grid.headroom_pct}%` : '--'}</strong>
        </div>
        <div className={`kpi status-${overview?.grid.severity ?? 'UNKNOWN'}`}>
          <span>Severity</span>
          <strong>{overview?.grid.severity ?? 'UNKNOWN'}</strong>
        </div>
      </section>

      <section className="workspace">
        <div className="diagram-card">
          <div className="actions">
            <button
              onClick={() => postAction('/api/simulate/ramp', { datacenter_id: 'dc-1', delta_mw: 12, duration_min: 20 })}
              disabled={busyAction !== null}
            >
              Simulate AI Ramp
            </button>
            <button
              className="secondary"
              onClick={() => postAction('/api/control/accept', { datacenter_id: 'dc-1', target_mw: 88 })}
              disabled={busyAction !== null}
            >
              Accept Coordinated Ramp
            </button>
          </div>

          <div className="sld-view" role="img" aria-label="Utility POI single line diagram">
            <svg viewBox="0 0 980 340" preserveAspectRatio="xMidYMid meet">
              {overview?.sld.edges.map((edge) => {
                const from = positions[edge.from];
                const to = positions[edge.to];
                if (!from || !to) return null;
                return (
                  <line
                    key={`${edge.from}-${edge.to}`}
                    x1={from.x + 44}
                    y1={from.y + 24}
                    x2={to.x}
                    y2={to.y + 24}
                    stroke="#5e6a7d"
                    strokeWidth="4"
                  />
                );
              })}

              {overview?.sld.nodes.map((node) => {
                const pos = positions[node.id] ?? { x: 0, y: 0 };
                return (
                  <g key={node.id} transform={`translate(${pos.x}, ${pos.y})`} onClick={() => setSelectedId(node.id)}>
                    <rect className={`node node-${node.status}`} width="88" height="48" rx="8" />
                    <text x="44" y="20" textAnchor="middle" className="node-label">
                      {node.type.replace('_', ' ')}
                    </text>
                    <text x="44" y="38" textAnchor="middle" className="node-value">
                      {node.value}
                    </text>
                  </g>
                );
              })}
            </svg>
            {overview?.sld.badges.map((badge) => {
              const pos = positions[badge.on];
              if (!pos) return null;
              return (
                <div
                  key={`${badge.on}-${badge.text}`}
                  className={`badge badge-${badge.severity}`}
                  style={{ left: `${pos.x}px`, top: `${pos.y - 44}px` }}
                >
                  {badge.text}
                </div>
              );
            })}
          </div>

          {overview && (
            <div className="coordination-strip">
              Coordination: <strong>{overview.sld.coordination.state}</strong> — {overview.sld.coordination.recommended_action}
            </div>
          )}

          {overview?.alerts.length ? (
            <div className="alerts">
              {overview.alerts.map((alert) => (
                <div key={alert.type} className="alert-red">
                  {alert.message}
                </div>
              ))}
            </div>
          ) : null}

          {error ? <div className="error">{error}</div> : null}
        </div>

        <aside className="details-drawer">
          <h2>Device Details</h2>
          {selectedNode ? (
            <>
              <p>
                <strong>{selectedNode.label}</strong>
              </p>
              <p>Type: {selectedNode.type}</p>
              <p>Status: {selectedNode.status}</p>
              <p>Value: {selectedNode.value}</p>
            </>
          ) : (
            <p>Select a symbol to inspect device details.</p>
          )}
        </aside>
      </section>
    </div>
  );
}

export default App;
