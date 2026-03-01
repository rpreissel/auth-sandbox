import { useState } from 'react';
import type { CmsPage, CmsPageRequest } from '../types';

interface Props {
  pages: CmsPage[];
  onRefresh: () => void;
  onDelete: (id: string) => void;
  onCreate: (payload: CmsPageRequest) => Promise<void>;
  count: number;
}

export function PagesTab({ pages, onRefresh, onDelete, onCreate, count }: Props) {
  const [isCreating, setIsCreating] = useState(false);
  const [form, setForm] = useState<CmsPageRequest>({
    name: '',
    key: '',
    protectionLevel: 'public',
    contentPath: '',
  });

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      await onCreate(form);
      setIsCreating(false);
      setForm({ name: '', key: '', protectionLevel: 'public', contentPath: '' });
    } catch {
      // error handled by parent
    }
  }

  return (
    <div className="tab-pane active">
      <div className="toolbar">
        <button className="btn btn-secondary btn-sm" onClick={onRefresh}>
          Refresh
        </button>
        <button className="btn btn-primary btn-sm" onClick={() => setIsCreating(!isCreating)}>
          {isCreating ? 'Cancel' : 'New Page'}
        </button>
        <span style={{ marginLeft: 'auto', color: '#666', fontSize: '0.875rem' }}>
          {count} page(s)
        </span>
      </div>

      {isCreating && (
        <form onSubmit={handleSubmit} style={{ marginBottom: '1.5rem', padding: '1rem', background: '#f9f9f9', borderRadius: '6px' }}>
          <div className="form-row">
            <div className="form-group">
              <label>Name</label>
              <input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="e.g., public"
                required
              />
            </div>
            <div className="form-group">
              <label>Key</label>
              <input
                value={form.key}
                onChange={(e) => setForm({ ...form, key: e.target.value })}
                placeholder="e.g., pub001"
                required
              />
            </div>
            <div className="form-group">
              <label>Protection Level</label>
              <select
                value={form.protectionLevel}
                onChange={(e) => setForm({ ...form, protectionLevel: e.target.value })}
              >
                <option value="public">public</option>
                <option value="acr1">acr1</option>
                <option value="acr2">acr2</option>
              </select>
            </div>
            <div className="form-group">
              <label>Content Path</label>
              <input
                value={form.contentPath}
                onChange={(e) => setForm({ ...form, contentPath: e.target.value })}
                placeholder="/cms-content/index.html"
                required
              />
            </div>
            <button type="submit" className="btn btn-primary btn-sm">
              Create
            </button>
          </div>
        </form>
      )}

      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Key</th>
            <th>Protection Level</th>
            <th>Content Path</th>
            <th>Created</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {pages.map((page) => (
            <tr key={page.id}>
              <td>{page.name}</td>
              <td>{page.key}</td>
              <td>{page.protectionLevel}</td>
              <td>{page.contentPath}</td>
              <td>{new Date(page.createdAt).toLocaleDateString()}</td>
              <td>
                <button
                  className="btn btn-danger btn-sm"
                  onClick={() => onDelete(page.id)}
                >
                  Delete
                </button>
              </td>
            </tr>
          ))}
          {pages.length === 0 && (
            <tr>
              <td colSpan={6} style={{ textAlign: 'center', color: '#666' }}>
                No pages yet
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
