/**
 * worker.js — Runs inside a worker_thread, processes one conversion job
 * Receives job data via workerData, posts progress back via parentPort
 */

const { workerData, parentPort } = require('worker_threads');
const path = require('path');
const fs = require('fs');
const sharp = require('sharp');
const ffmpeg = require('fluent-ffmpeg');
const ffmpegPath = require('ffmpeg-static');
ffmpeg.setFfmpegPath(ffmpegPath);

const job = workerData;

function progress(pct, message) {
  parentPort.postMessage({ type: 'progress', jobId: job.jobId, progress: pct, message });
}

async function convertImage(inputPath, outputPath, targetFormat) {
  progress(10, 'Reading image...');
  const img = sharp(inputPath);
  progress(40, `Converting to ${targetFormat.toUpperCase()}...`);
  const fmt = targetFormat.toLowerCase();
  if (fmt === 'jpg' || fmt === 'jpeg') {
    await img.jpeg({ quality: 90 }).toFile(outputPath);
  } else if (fmt === 'png') {
    await img.png({ compressionLevel: 7 }).toFile(outputPath);
  } else if (fmt === 'webp') {
    await img.webp({ quality: 90 }).toFile(outputPath);
  } else if (fmt === 'avif') {
    await img.avif({ quality: 80 }).toFile(outputPath);
  } else {
    throw new Error(`Unsupported image format: ${targetFormat}`);
  }
  progress(90, 'Saving output...');
}

async function convertText(inputPath, outputPath, targetFormat) {
  progress(20, 'Reading file...');
  const content = fs.readFileSync(inputPath, 'utf8');
  progress(60, 'Converting...');
  if (targetFormat === 'txt') {
    fs.writeFileSync(outputPath, content, 'utf8');
  } else if (targetFormat === 'json') {
    // wrap plain text in a JSON envelope
    const wrapped = JSON.stringify({ content, source: path.basename(inputPath) }, null, 2);
    fs.writeFileSync(outputPath, wrapped, 'utf8');
  } else if (targetFormat === 'md') {
    fs.writeFileSync(outputPath, content, 'utf8');
  } else if (targetFormat === 'html') {
    const escaped = content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    const html = `<!DOCTYPE html>\n<html><head><meta charset="utf-8"><title>Converted</title></head><body><pre>${escaped}</pre></body></html>`;
    fs.writeFileSync(outputPath, html, 'utf8');
  } else {
    throw new Error(`Unsupported text format: ${targetFormat}`);
  }
  progress(90, 'Saving output...');
}

async function convertVideo(inputPath, outputPath, targetFormat) {
  return new Promise((resolve, reject) => {
    progress(10, 'Reading video...');
    
    const command = ffmpeg(inputPath)
      .output(outputPath)
      .outputOptions(['-c:v', 'libx264', '-c:a', 'aac', '-q:v', '5']);

    // Handle format-specific options
    const fmt = targetFormat.toLowerCase();
    if (fmt === 'mp4') {
      command.format('mp4');
    } else if (fmt === 'mkv') {
      command.format('matroska');
    } else if (fmt === 'avi') {
      command.format('avi');
    } else if (fmt === 'mov') {
      command.format('mov');
    } else if (fmt === 'webm') {
      command.outputOptions(['-c:v', 'libvpx', '-c:a', 'libopus']);
      command.format('webm');
    } else {
      return reject(new Error(`Unsupported video format: ${targetFormat}`));
    }

    let lastProgress = 0;

    command
      .on('progress', (data) => {
        // Estimate progress based on timemark
        if (data.timemark) {
          const timeParts = data.timemark.split(':');
          const seconds = parseInt(timeParts[0]) * 3600 + parseInt(timeParts[1]) * 60 + parseInt(timeParts[2]);
          const pct = Math.min(90, Math.floor(10 + (seconds / 10) * 80)); // scale to 10-90%
          if (pct > lastProgress) {
            lastProgress = pct;
            progress(pct, `Converting video... ${pct}%`);
          }
        }
      })
      .on('error', (err) => {
        reject(new Error(`Video conversion failed: ${err.message}`));
      })
      .on('end', () => {
        progress(100, 'Video conversion complete');
        resolve();
      })
      .run();
  });
}

(async () => {
  try {
    progress(5, 'Starting conversion...');
    const ext = path.extname(job.originalName).slice(1).toLowerCase();
    const imageFormats = ['jpg', 'jpeg', 'png', 'webp', 'avif', 'gif', 'tiff'];
    const textFormats = ['txt', 'md', 'json', 'html', 'csv', 'xml'];
    const videoFormats = ['mp4', 'mkv', 'avi', 'mov', 'webm', 'flv', 'wmv', 'mov'];

    if (imageFormats.includes(ext) && imageFormats.includes(job.targetFormat)) {
      await convertImage(job.inputPath, job.outputPath, job.targetFormat);
    } else if (textFormats.includes(ext) && textFormats.includes(job.targetFormat)) {
      await convertText(job.inputPath, job.outputPath, job.targetFormat);
    } else if (videoFormats.includes(ext) && videoFormats.includes(job.targetFormat)) {
      await convertVideo(job.inputPath, job.outputPath, job.targetFormat);
    } else {
      throw new Error(`Conversion from .${ext} to .${job.targetFormat} is not supported in this MVP.`);
    }

    progress(100, 'Done');
    parentPort.postMessage({ type: 'done', jobId: job.jobId, outputPath: job.outputPath });
  } catch (err) {
    parentPort.postMessage({ type: 'error', jobId: job.jobId, error: err.message });
  }
})();
