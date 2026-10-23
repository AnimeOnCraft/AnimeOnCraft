import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@animeoncraft/ui/styles.css';
import './launcher.css';
import { LauncherApp, LauncherBoundary } from './App.js';
const root = document.getElementById('root');
if (!root) throw new Error('Missing #root element');
createRoot(root).render(<StrictMode><LauncherBoundary><LauncherApp /></LauncherBoundary></StrictMode>);
