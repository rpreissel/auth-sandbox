import { useEffect, useRef } from 'react';
import type { LogEntry } from '../types';

interface Props {
  entries: LogEntry[];
  onClear: () => void;
}

export function ActivityLog({ entries, onClear }: Props) {
  const logRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [entries]);

  return (
    <div className="card">
      <div className="card-header">
        Activity Log
        <button className="btn btn-secondary btn-sm" onClick={onClear} style={{ marginLeft: 'auto' }}>
          Clear
        </button>
      </div>
      <div className="card-body" style={{ padding: '.75rem' }}>
        <div id="log" ref={logRef}>
          {entries.map(entry => (
            <div key={entry.id} className={`log-entry ${entry.level}`}>
              <span className="ts">{entry.ts}</span>
              <span className="msg">{entry.msg}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
