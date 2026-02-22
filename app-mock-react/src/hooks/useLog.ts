import { useState, useCallback, useRef } from 'react';
import type { LogEntry } from '../types';

let nextId = 0;

export function useLog() {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const ref = useRef(entries);
  ref.current = entries;

  const log = useCallback((msg: string, level: LogEntry['level'] = 'info') => {
    const ts = new Date().toTimeString().slice(0, 8);
    setEntries(prev => [...prev, { id: nextId++, ts, msg, level }]);
  }, []);

  const clear = useCallback(() => setEntries([]), []);

  return { entries, log, clear };
}
