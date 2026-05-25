import React, { useState, useEffect, useRef, useCallback } from 'react';
import socket from './services/socket';
import { uploadFiles, fetchJobs, fetchStats, clearJobs, downloadUrl, zipUrl } from './services/api';
import './App.css';

// ── Format groups ─────────────────────────────────────────────────────────────
const FORMAT_GROUPS = {
  Image: ['jpg', 'png', 'webp', 'avif'],
  Text:  ['txt', 'md', 'json', 'html'],
  Video: ['mp4', 'mkv', 'webm', 'avi', 'mov'],
};

const FORMAT_SOURCE_MAP = {
  jpg:  ['jpeg','png','webp','avif','gif','tiff'],
  png:  ['jpeg','jpg','webp','avif','gif','tiff'],
  webp: ['jpeg','jpg','png','avif','gif','tiff'],
  avif: ['jpeg','jpg','png','webp','gif','tiff'],
  txt:  ['txt','md','json','html','csv','xml'],
  md:   ['txt','md','html','csv','xml'],
  json: ['txt','md','csv','xml'],
  html: ['txt','md','json','csv','xml'],
  mp4:  ['mp4','mkv','avi','mov','webm','flv','wmv'],
  mkv:  ['mp4','mkv','avi','mov','webm','flv','wmv'],
  webm: ['mp4','mkv','avi','mov','webm','flv','wmv'],
  avi:  ['mp4','mkv','mov','webm','flv','wmv'],
  mov:  ['mp4','mkv','avi','webm','flv','wmv'],
};

function fileIcon(name) {
  const ext = name.split('.').pop().toLowerCase();
  const img = ['jpg','jpeg','png','webp','avif','gif','tiff'];
  if (img.includes(ext)) return '🖼';
  const doc = ['txt','md','json','html','csv','xml'];
  if (doc.includes(ext)) return '📄';
  const vid = ['mp4','mkv','avi','mov','webm','flv','wmv'];
  if (vid.includes(ext)) return '🎬';
  return '📁';
}

function formatBytes(bytes) {
  if (!bytes) return '—';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

function elapsed(job) {
  if (!job.completedAt || !job.createdAt) return '';
  const s = ((job.completedAt - job.createdAt) / 1000).toFixed(1);
  return `${s}s`;
}

// ── StatusBadge ───────────────────────────────────────────────────────────────
function StatusBadge({ status }) {
  const map = {
    queued: { label: 'QUEUED', cls: 'badge-queued' },
    processing: { label: 'PROCESSING', cls: 'badge-processing' },
    completed: { label: 'DONE', cls: 'badge-done' },
    failed: { label: 'FAILED', cls: 'badge-failed' },
  };
  const { label, cls } = map[status] || { label: status, cls: '' };
  return <span className={`badge ${cls}`}>{label}</span>;
}

// ── ProgressBar ───────────────────────────────────────────────────────────────
function ProgressBar({ pct, status }) {
  return (
    <div className="progress-track">
      <div
        className={`progress-fill ${status === 'completed' ? 'progress-done' : ''} ${status === 'failed' ? 'progress-fail' : ''}`}
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}

// ── JobRow ────────────────────────────────────────────────────────────────────
function JobRow({ job, selected, onToggle }) {
  return (
    <div className={`job-row ${job.status} ${selected ? 'job-selected' : ''}`} onClick={onToggle}>
      <div className="job-check">
        {job.status === 'completed' && (
          <input
            type="checkbox"
            checked={selected}
            onChange={onToggle}
            onClick={(e) => e.stopPropagation()}
          />
        )}
      </div>
      <div className="job-icon">{fileIcon(job.originalName)}</div>
      <div className="job-info">
        <div className="job-name">{job.originalName}</div>
        <div className="job-meta">
          {formatBytes(job.fileSize)}
          {job.targetFormat && <span className="job-arrow">→ .{job.targetFormat}</span>}
          {elapsed(job) && <span className="job-elapsed">{elapsed(job)}</span>}
        </div>
        {(job.status === 'processing' || job.status === 'queued') && (
          <div className="job-msg">{job.progressMessage || 'Waiting...'}</div>
        )}
        {job.error && <div className="job-error">{job.error}</div>}
      </div>
      <div className="job-right">
        <StatusBadge status={job.status} />
        <ProgressBar pct={job.progress || 0} status={job.status} />
        {job.status === 'completed' && (
          <a
            className="dl-btn"
            href={downloadUrl(job.jobId)}
            onClick={(e) => e.stopPropagation()}
          >
            ↓
          </a>
        )}
      </div>
    </div>
  );
}

// ── DropZone ──────────────────────────────────────────────────────────────────
function DropZone({ onFiles }) {
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef();

  const handleDrop = useCallback((e) => {
    e.preventDefault();
    setDragging(false);
    const files = Array.from(e.dataTransfer.files);
    if (files.length) onFiles(files);
  }, [onFiles]);

  return (
    <div
      className={`dropzone ${dragging ? 'dropzone-over' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={handleDrop}
      onClick={() => inputRef.current.click()}
    >
      <div className="dropzone-glyph">⬡</div>
      <div className="dropzone-label">DROP FILES HERE</div>
      <div className="dropzone-sub">or click to browse · max 500 MB per file · up to 50 files</div>
      <input
        ref={inputRef}
        type="file"
        multiple
        style={{ display: 'none' }}
        onChange={(e) => { if (e.target.files.length) onFiles(Array.from(e.target.files)); e.target.value = ''; }}
      />
    </div>
  );
}

// ── Main App ──────────────────────────────────────────────────────────────────
export default function App() {
  const [jobs, setJobs] = useState([]);
  const [stats, setStats] = useState({ activeWorkers: 0, maxWorkers: 4, queueSize: 0 });
  const [stagedFiles, setStagedFiles] = useState([]);
  const [targetFormat, setTargetFormat] = useState('png');
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [socketConnected, setSocketConnected] = useState(false);
  const [toast, setToast] = useState(null);

  // ── Load initial jobs ─────────────────────────────────────────────────────
  useEffect(() => {
    fetchJobs().then(setJobs).catch(() => {});
    fetchStats().then(setStats).catch(() => {});
  }, []);

  // ── Socket listeners ──────────────────────────────────────────────────────
  useEffect(() => {
    socket.on('connect', () => setSocketConnected(true));
    socket.on('disconnect', () => setSocketConnected(false));

    const refresh = () => {
      fetchJobs().then(setJobs).catch(() => {});
      fetchStats().then(setStats).catch(() => {});
    };

    socket.on('job:processing', refresh);
    socket.on('job:progress', (data) => {
      setJobs((prev) =>
        prev.map((j) =>
          j.jobId === data.jobId
            ? { ...j, progress: data.progress, progressMessage: data.message, status: 'processing' }
            : j
        )
      );
    });
    socket.on('job:done', refresh);
    socket.on('job:failed', refresh);
    socket.on('job:requeued', refresh);

    return () => socket.off();
  }, []);

  // ── Toast helper ──────────────────────────────────────────────────────────
  function showToast(msg, type = 'info') {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3000);
  }

  // ── Handlers ──────────────────────────────────────────────────────────────
  function handleFiles(files) {
    setStagedFiles((prev) => {
      const existing = new Set(prev.map((f) => f.name));
      const newOnes = files.filter((f) => !existing.has(f.name));
      return [...prev, ...newOnes];
    });
  }

  function removeStaged(name) {
    setStagedFiles((prev) => prev.filter((f) => f.name !== name));
  }

  async function handleConvert() {
    if (!stagedFiles.length) return;
    setUploading(true);
    setUploadProgress(0);
    try {
      const newJobs = await uploadFiles(stagedFiles, targetFormat, setUploadProgress);
      setJobs((prev) => [...newJobs, ...prev]);
      setStagedFiles([]);
      showToast(`${newJobs.length} job${newJobs.length > 1 ? 's' : ''} queued`, 'success');
    } catch (e) {
      showToast(e.response?.data?.error || 'Upload failed', 'error');
    } finally {
      setUploading(false);
      setUploadProgress(0);
    }
  }

  function toggleSelect(jobId) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      next.has(jobId) ? next.delete(jobId) : next.add(jobId);
      return next;
    });
  }

  async function handleClear() {
    await clearJobs().catch(() => {});
    setJobs([]);
    setSelectedIds(new Set());
  }

  function handleBatchDownload() {
    const ids = Array.from(selectedIds);
    if (!ids.length) return;
    window.location.href = zipUrl(ids);
  }

  const completedJobs = jobs.filter((j) => j.status === 'completed');
  const activeJobs = jobs.filter((j) => j.status === 'processing' || j.status === 'queued');

  return (
    <div className="app">
      {/* Noise overlay */}
      <div className="noise" />

      {/* Header */}
      <header className="header">
        <div className="header-left">
          <div className="logo">
            <span className="logo-hex">⬡</span>
            <span className="logo-text">THREADCONV</span>
          </div>
          <div className="tagline">multithreaded file conversion</div>
        </div>
        <div className="header-right">
          <div className="stat-chip">
            <span className="stat-label">WORKERS</span>
            <span className="stat-val">{stats.activeWorkers}/{stats.maxWorkers}</span>
          </div>
          <div className="stat-chip">
            <span className="stat-label">QUEUE</span>
            <span className="stat-val">{stats.queueSize}</span>
          </div>
          <div className={`socket-dot ${socketConnected ? 'dot-on' : 'dot-off'}`} title={socketConnected ? 'Live' : 'Disconnected'} />
        </div>
      </header>

      <main className="main">
        {/* Left panel: Upload */}
        <section className="panel panel-upload">
          <div className="panel-title">01 — UPLOAD</div>

          <DropZone onFiles={handleFiles} />

          {stagedFiles.length > 0 && (
            <div className="staged-list">
              {stagedFiles.map((f) => (
                <div key={f.name} className="staged-item">
                  <span>{fileIcon(f.name)}</span>
                  <span className="staged-name">{f.name}</span>
                  <span className="staged-size">{formatBytes(f.size)}</span>
                  <button className="staged-remove" onClick={() => removeStaged(f.name)}>✕</button>
                </div>
              ))}
            </div>
          )}

          <div className="panel-title panel-title-mt">02 — TARGET FORMAT</div>
          <div className="format-groups">
            {Object.entries(FORMAT_GROUPS).map(([group, formats]) => (
              <div key={group} className="format-group">
                <div className="format-group-label">{group}</div>
                <div className="format-pills">
                  {formats.map((fmt) => (
                    <button
                      key={fmt}
                      className={`pill ${targetFormat === fmt ? 'pill-active' : ''}`}
                      onClick={() => setTargetFormat(fmt)}
                    >
                      .{fmt}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>

          <button
            className={`convert-btn ${uploading ? 'btn-loading' : ''}`}
            disabled={!stagedFiles.length || uploading}
            onClick={handleConvert}
          >
            {uploading ? (
              <>
                <span className="spinner" />
                UPLOADING {uploadProgress}%
              </>
            ) : (
              <>CONVERT {stagedFiles.length > 0 ? `${stagedFiles.length} FILE${stagedFiles.length > 1 ? 'S' : ''}` : ''}</>
            )}
          </button>

          {uploading && (
            <div className="upload-bar-track">
              <div className="upload-bar-fill" style={{ width: `${uploadProgress}%` }} />
            </div>
          )}
        </section>

        {/* Right panel: Jobs */}
        <section className="panel panel-jobs">
          <div className="jobs-header">
            <div className="panel-title">03 — JOBS</div>
            <div className="jobs-actions">
              {selectedIds.size > 0 && (
                <button className="action-btn" onClick={handleBatchDownload}>
                  ↓ ZIP ({selectedIds.size})
                </button>
              )}
              {jobs.length > 0 && (
                <button className="action-btn action-clear" onClick={handleClear}>
                  CLEAR
                </button>
              )}
            </div>
          </div>

          {activeJobs.length > 0 && (
            <div className="jobs-section-label">ACTIVE</div>
          )}
          {activeJobs.map((job) => (
            <JobRow key={job.jobId} job={job} selected={false} onToggle={() => {}} />
          ))}

          {completedJobs.length > 0 && (
            <div className="jobs-section-label">COMPLETED</div>
          )}
          {completedJobs.map((job) => (
            <JobRow
              key={job.jobId}
              job={job}
              selected={selectedIds.has(job.jobId)}
              onToggle={() => toggleSelect(job.jobId)}
            />
          ))}

          {jobs.filter(j => j.status === 'failed').length > 0 && (
            <div className="jobs-section-label">FAILED</div>
          )}
          {jobs.filter(j => j.status === 'failed').map((job) => (
            <JobRow key={job.jobId} job={job} selected={false} onToggle={() => {}} />
          ))}

          {jobs.length === 0 && (
            <div className="jobs-empty">
              <div className="empty-glyph">◈</div>
              <div className="empty-text">No jobs yet. Upload files to begin.</div>
            </div>
          )}
        </section>
      </main>

      {/* Toast */}
      {toast && (
        <div className={`toast toast-${toast.type}`}>{toast.msg}</div>
      )}
    </div>
  );
}
