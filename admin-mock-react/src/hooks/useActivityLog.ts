import { useCallback, useRef, useState } from 'react';
import type { LogEntry, LogLevel } from '../types';

let nextId = 0;

export function useActivityLog() {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const counterRef = useRef(nextId);

  const addEntry = useCallback((msg: string, level: LogLevel = '') => {
    const id = counterRef.current++;
    const ts = new Date().toTimeString().slice(0, 8);
    setEntries(prev => [...prev, { id, ts, msg, level }]);
  }, []);

  const clearEntries = useCallback(() => setEntries([]), []);

  return { entries, addEntry, clearEntries };
}
