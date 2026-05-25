import axios from 'axios';

const api = axios.create({ baseURL: 'http://localhost:3001/api' });

export async function uploadFiles(files, targetFormat, onProgress) {
  const formData = new FormData();
  files.forEach((f) => formData.append('files', f));
  formData.append('targetFormat', targetFormat);
  const res = await api.post('/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => {
      if (onProgress) onProgress(Math.round((e.loaded * 100) / e.total));
    },
  });
  return res.data.jobs;
}

export async function fetchJobs() {
  const res = await api.get('/jobs');
  return res.data.jobs;
}

export async function fetchStats() {
  const res = await api.get('/stats');
  return res.data;
}

export async function clearJobs() {
  return api.delete('/jobs/clear');
}

export function downloadUrl(jobId) {
  return `http://localhost:3001/api/download/${jobId}`;
}

export function zipUrl(jobIds) {
  return `http://localhost:3001/api/download-zip?ids=${jobIds.join(',')}`;
}
