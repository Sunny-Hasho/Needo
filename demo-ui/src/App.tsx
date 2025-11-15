import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { 
  Cloud, HardDrive, LayoutGrid as Layout, Server, Upload, Trash2, 
  Download, Activity, CheckCircle, XCircle, File as FileIcon, 
  X, Zap, Shield, Info, RefreshCw
} from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';

const API_URL = 'http://localhost:8080';

// Types
interface NodeHealth {
  upNodesCount: number;
  downNodesCount: number;
  upNodeUrls: string[];
  downNodeUrls: string[];
  totalNodes: number;
  writeQuorum: number;
  readQuorum: number;
}

interface ZabClusterStatus {
  isLeader: boolean;
  leader: string | null;
  monitoringActive: boolean;
  committedOperations: number;
  nodesStatus?: Record<string, string>;
}

interface ClusterHealth {
  status: string;
  gatewayNodeId: string;
  lamportClock: number;
  storage: NodeHealth;
  zabCluster: ZabClusterStatus;
}

interface Manifest {
  fileId: string;
  chunkIds: string[];
  replicas: Record<string, string[]>;
  version: number;
  timestamp: number;
  chunkCount: number;
}

interface ModalState {
  type: 'confirm' | 'alert' | 'error';
  title: string;
  message: string;
  onConfirm?: () => void;
  onCancel?: () => void;
  confirmLabel?: string;
  cancelLabel?: string;
  isDestructive?: boolean;
}

interface NotificationState {
  message: string;
  type: 'success' | 'error';
}

export default function App() {
  const [activeTab, setActiveTab] = useState<'files' | 'dashboard'>('files');
  const [health, setHealth] = useState<ClusterHealth | null>(null);
  const [files, setFiles] = useState<Manifest[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const [selectedFileId, setSelectedFileId] = useState<string | null>(null);
  const [activeModal, setActiveModal] = useState<ModalState | null>(null);
  const [notification, setNotification] = useState<NotificationState | null>(null);

  const showNotification = (message: string, type: 'success' | 'error' = 'success') => {
    setNotification({ message, type });
    setTimeout(() => setNotification(null), 4000);
  };

  // Poll Health
  useEffect(() => {
    const fetchHealth = async () => {
      try {
        const res = await axios.get(`${API_URL}/dashboard/stats`);
        setHealth(res.data);
      } catch (e) {
        console.error("Failed to fetch health", e);
      }
    };
    fetchHealth();
    const interval = setInterval(fetchHealth, 2000);
    return () => clearInterval(interval);
  }, []);

  // Poll Files
  useEffect(() => {
    const fetchFiles = async () => {
      if (activeTab === 'files') {
        try {
          const res = await axios.get(`${API_URL}/files`);
          const fileList = Object.values(res.data) as Manifest[];
          fileList.sort((a, b) => b.timestamp - a.timestamp);
          setFiles(fileList);
        } catch (e) {
          console.error("Failed to fetch files", e);
        }
      }
    };
    fetchFiles();
    const interval = setInterval(fetchFiles, 3000);
    return () => clearInterval(interval);
  }, [activeTab]);

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setIsUploading(true);
    const formData = new FormData();
    formData.append('file', file);

    try {
      await axios.post(`${API_URL}/files`, formData);
      showNotification("Upload successful");
    } catch (err) {
      setActiveModal({
        type: 'error',
        title: 'Upload Failed',
        message: 'Could not complete the file upload. Please check your network connection.',
        confirmLabel: 'OK'
      });
    } finally {
      setIsUploading(false);
      if (e.target) e.target.value = '';
    }
  };

  const handleDownload = (filename: string) => {
    window.open(`${API_URL}/files/${filename}`, '_blank');
    showNotification("Download started");
  };

  const handleDelete = async (filename: string) => {
    setActiveModal({
      type: 'confirm',
      title: 'Delete Shard',
      message: `Are you sure you want to permanently remove "${filename}" from the distributed archive?`,
      confirmLabel: 'Delete',
      cancelLabel: 'Cancel',
      isDestructive: true,
      onConfirm: async () => {
        try {
          await axios.delete(`${API_URL}/files/${filename}`);
          setActiveModal(null);
          showNotification("File data deleted");
        } catch (e) {
          setActiveModal({
            type: 'error',
            title: 'Action Failed',
            message: 'An error occurred while deleting the file chunks.',
            confirmLabel: 'OK'
          });
        }
      }
    });
  };

  const handleToggleNode = async (url: string) => {
    const port = url.split(':').pop();
    const isCurrentlyUp = health?.storage.upNodeUrls.includes(url);
    const action = isCurrentlyUp ? "Deactivate" : "Activate";
    
    setActiveModal({
      type: 'confirm',
      title: `${action} Node ${port}`,
      message: isCurrentlyUp 
        ? `Are you sure you want to deactivate Node ${port}? This will simulate a cluster partition or failure.`
        : `Activate Node ${port} and rejoin it to the distributed cluster?`,
      confirmLabel: isCurrentlyUp ? 'Deactivate' : 'Activate',
      cancelLabel: 'Cancel',
      isDestructive: isCurrentlyUp,
      onConfirm: async () => {
        try {
          await axios.post(`${API_URL}/api/chaos/toggle`, { url });
          setActiveModal(null);
          showNotification(`Node ${port} ${isCurrentlyUp ? 'deactivated' : 'activated'}`);
        } catch (e) {
          setActiveModal({
            type: 'error',
            title: 'Command Failed',
            message: 'The chaos command could not be delivered to the gateway.',
            confirmLabel: 'OK'
          });
        }
      }
    });
  };

  return (
    <div className="flex h-screen bg-white text-apple-900 font-sans">
      {/* Apple-style Sidebar */}
      <aside className="w-64 bg-apple-50 border-r border-apple-200 flex flex-col p-6 space-y-8">
        <div className="flex items-center space-x-3 px-1">
           <img src="/needo.png" alt="Needo" className="w-8 h-8 object-contain" />
           <span className="text-lg font-bold tracking-tight text-apple-900">Needo</span>
        </div>

        <nav className="flex-1 space-y-1">
          <button 
            onClick={() => setActiveTab('files')}
            className={`flex items-center space-x-3 w-full px-4 py-2.5 rounded-apple transition-colors text-sm font-medium ${
              activeTab === 'files' ? 'bg-apple-200 text-apple-900' : 'text-apple-500 hover:bg-apple-100 hover:text-apple-900'
            }`}
          >
            <HardDrive strokeWidth={1.2} size={18} />
            <span>Files</span>
          </button>
          <button 
            onClick={() => setActiveTab('dashboard')}
            className={`flex items-center space-x-3 w-full px-4 py-2.5 rounded-apple transition-colors text-sm font-medium ${
              activeTab === 'dashboard' ? 'bg-apple-200 text-apple-900' : 'text-apple-500 hover:bg-apple-100 hover:text-apple-900'
            }`}
          >
            <Layout strokeWidth={1.2} size={18} />
            <span>Dashboard</span>
          </button>
        </nav>

        {/* Global Monitor Placeholder */}
        {health && (
          <div className="px-2 pb-2">
             <div className="flex items-center justify-between text-[10px] text-apple-500 font-medium uppercase tracking-wider mb-2">
               <span>System Status</span>
               <span className="text-apple-900">{health.storage.upNodesCount}/{health.storage.totalNodes}</span>
             </div>
             <div className="h-1 bg-apple-200 rounded-full overflow-hidden">
                <div 
                  className="h-full bg-apple-900 transition-all duration-1000"
                  style={{ width: `${(health.storage.upNodesCount / health.storage.totalNodes) * 100}%` }}
                />
             </div>
          </div>
        )}
      </aside>

      {/* Main Content */}
      <main className="flex-1 overflow-auto flex flex-col">
        <header className="px-8 py-6 flex items-center justify-between sticky top-0 bg-white/80 backdrop-blur-xl z-20 border-b border-apple-100">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight text-black">
              {activeTab === 'files' ? 'Archive' : 'Dashboard'}
            </h1>
            <p className="text-apple-500 text-[12px] mt-1">
              {activeTab === 'files' ? 'Distributed storage chunks.' : 'System replication health.'}
            </p>
          </div>

          <div className="flex items-center space-x-3">
            {activeTab === 'files' && (
              <div className="flex items-center space-x-2">
                <input type="file" id="file-upload" className="hidden" onChange={handleUpload} disabled={isUploading} />
                <label 
                  htmlFor="file-upload" 
                  className={`apple-button-primary cursor-pointer flex items-center space-x-2 text-sm shadow-md transition-all active:scale-95 px-5 py-2
                    ${isUploading ? 'opacity-50 cursor-not-allowed' : ''}`}
                >
                  {isUploading ? <RefreshCw className="animate-spin w-4 h-4" /> : <Upload strokeWidth={1.5} size={16} />}
                  <span>{isUploading ? 'Uploading...' : 'Upload'}</span>
                </label>
              </div>
            )}
            {health && (activeTab === 'dashboard') && (
               <div className="px-3 py-1.5 apple-card border-none text-[10px] text-apple-500 font-mono">
                  CLOCK: {health.lamportClock}
               </div>
            )}
          </div>
        </header>

        <section className="px-8 py-6 flex-1">
          {activeTab === 'files' ? (
            <div className="space-y-3 animate-in fade-in duration-700">
               {files.length === 0 ? (
                 <div className="apple-card p-12 text-center border-dashed">
                    <Cloud strokeWidth={1} size={40} className="mx-auto text-apple-200 mb-3" />
                    <p className="text-apple-500 text-sm font-medium">Your archive is empty.</p>
                 </div>
               ) : (
                 <div className="grid grid-cols-1 gap-1.5">
                    {files.map(f => (
                      <div key={f.fileId} className="flex items-center justify-between p-4 apple-card hover:bg-apple-100/50 transition-all duration-200 group border-none">
                         <div className="flex items-center space-x-4">
                            <div className="w-10 h-10 bg-white rounded-lg flex items-center justify-center shadow-sm border border-apple-100">
                               <FileIcon strokeWidth={1.2} size={20} className="text-apple-900" />
                            </div>
                            <div>
                               <h3 className="text-[15px] font-medium text-black truncate max-w-xs">{f.fileId}</h3>
                               <p className="text-[12px] text-apple-500 mt-0.5 opacity-80">
                                 {formatDistanceToNow(new Date(f.timestamp), { addSuffix: true })} • {f.chunkCount} Nodes
                               </p>
                            </div>
                         </div>
                         <div className="flex items-center space-x-1">
                            <button 
                              onClick={() => setSelectedFileId(f.fileId)}
                               className="p-2 text-apple-500 hover:text-black hover:bg-white rounded-full transition-all"
                               title="Details"
                            >
                              <Info strokeWidth={1.2} size={18} />
                            </button>
                            <button 
                              onClick={() => handleDownload(f.fileId)}
                              className="p-2 text-apple-500 hover:text-black hover:bg-white rounded-full transition-all"
                              title="Download"
                            >
                              <Download strokeWidth={1.2} size={18} />
                            </button>
                            <button 
                              onClick={() => handleDelete(f.fileId)}
                              className="p-2 text-apple-500 hover:text-status-error hover:bg-white rounded-full transition-all"
                              title="Delete"
                            >
                              <Trash2 strokeWidth={1.2} size={18} />
                            </button>
                         </div>
                      </div>
                    ))}
                 </div>
               )}

               {/* Simple Apple Modal for Chunks */}
               {selectedFileId && (
                 <div className="fixed inset-0 bg-white/60 backdrop-blur-2xl flex items-center justify-center z-50 p-4 animate-in fade-in duration-300">
                    <div className="bg-white border border-apple-200 p-8 rounded-apple-xl w-full max-w-md shadow-2xl relative">
                       <button onClick={() => setSelectedFileId(null)} className="absolute top-5 right-5 text-apple-500 hover:text-black">
                          <X strokeWidth={1.2} size={20} />
                       </button>
                       <h3 className="text-xl font-semibold tracking-tight mb-1">Chunk Distribution</h3>
                       <p className="text-apple-500 text-xs mb-6 truncate">{selectedFileId}</p>
                       
                       <div className="space-y-2 max-h-[35vh] overflow-y-auto px-1">
                          {files.find(f => f.fileId === selectedFileId)?.chunkIds.map((cid, idx) => (
                            <div key={cid} className="p-3 apple-card border-none bg-apple-100/50 flex flex-col gap-1.5">
                               <span className="text-[9px] font-bold text-apple-500 uppercase tracking-widest opacity-80">Chunk {idx + 1}</span>
                               <div className="flex flex-wrap gap-1.5">
                                  {files.find(f => f.fileId === selectedFileId)?.replicas[cid]?.map(url => (
                                    <div key={url} className="px-2 py-0.5 bg-white text-[10px] font-medium rounded-full border border-apple-100 flex items-center gap-1">
                                       <div className={`w-1 h-1 rounded-full ${health?.storage.downNodeUrls.includes(url) ? 'bg-status-error' : 'bg-status-success'}`} />
                                       Port {url.split(':').pop()}
                                    </div>
                                  ))}
                                </div>
                            </div>
                          ))}
                       </div>
                    </div>
                 </div>
               )}
            </div>
          ) : (
            <div className="space-y-6 animate-in fade-in duration-700">
               {health ? (
                 <>
                   {/* Balanced Status Cards - Compact */}
                   <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                      <div className="p-5 apple-card border-none bg-apple-100/30">
                         <div className="flex items-center space-x-2 text-apple-500 mb-3 font-semibold tracking-widest text-[9px] uppercase">
                            <Server strokeWidth={1.2} size={14} />
                            <span>Storage Cluster</span>
                         </div>
                         <h2 className="text-xl font-medium tracking-tight mt-0.5">
                           {health.storage.upNodesCount} <span className="text-apple-500 font-light">/</span> {health.storage.totalNodes}
                         </h2>
                      </div>
                      <div className="p-5 apple-card border-none bg-apple-100/30">
                         <div className="flex items-center space-x-2 text-apple-500 mb-3 font-semibold tracking-widest text-[9px] uppercase">
                            <Activity strokeWidth={1.2} size={14} />
                            <span>Read/Write</span>
                         </div>
                         <h2 className="text-xl font-medium tracking-tight mt-0.5">
                            W{health.storage.writeQuorum} <span className="text-apple-500 font-light">•</span> R{health.storage.readQuorum}
                         </h2>
                      </div>
                      <div className="p-5 apple-card border-none bg-apple-100/30">
                         <div className="flex items-center space-x-2 text-apple-500 mb-3 font-semibold tracking-widest text-[9px] uppercase">
                            <Shield strokeWidth={1.2} size={14} />
                            <span>Metadata Leader</span>
                         </div>
                         <h2 className="text-lg font-medium tracking-tight mt-0.5 truncate ">
                            {health.zabCluster.leader ? `Metadata Node ${health.zabCluster.leader.split('-').pop()}` : "No Leader"}
                         </h2>
                      </div>
                   </div>

                   {/* Consistent Management Section - Compact Dark Edition */}
                   <div className="p-8 apple-card border-none bg-apple-900 text-white rounded-apple-xl shadow-xl overflow-hidden relative">
                      {/* Subtle Apple-style background gradient/glass */}
                      <div className="absolute -top-32 -right-32 w-64 h-64 bg-apple-500/10 rounded-full blur-3xl"></div>
                      
                      <div className="flex items-center justify-between mb-8 relative z-10 px-2">
                         <div>
                            <h2 className="text-xl font-semibold tracking-tight">Storage Node Control</h2>
                            <p className="text-apple-500 text-[12px] mt-0.5 font-medium opacity-80">Tap a node to perform health checks.</p>
                         </div>
                         <Zap strokeWidth={1} size={22} className="text-white opacity-60" />
                      </div>
                      
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 relative z-10">
                         {[9001, 9002, 9003, 9004].map(port => {
                           const isUp = health.storage.upNodeUrls.includes(`http://localhost:${port}`);
                           return (
                             <button 
                                key={port} 
                                onClick={() => handleToggleNode(`http://localhost:${port}`)}
                                className={`p-4 rounded-lg border transition-all text-left group active:scale-95 ${
                                  isUp 
                                    ? 'bg-white/10 border-white/5 hover:bg-white/20' 
                                    : 'bg-white/5 border-white/5 hover:bg-white/10 opacity-60 grayscale-[0.5]'
                                }`}
                             >
                                <div className="flex justify-between items-start mb-4">
                                   <span className="text-[8px] uppercase tracking-widest font-bold text-apple-500 opacity-60">ID::{port}</span>
                                   <div className={`w-2 h-2 rounded-full ${isUp ? 'bg-status-success shadow-[0_0_8px_#34c759]' : 'bg-status-error'}`} />
                                </div>
                                <span className="text-[14px] font-medium block leading-none">{isUp ? 'Online' : 'Offline'}</span>
                                <span className="text-[10px] text-apple-500 mt-2 block font-medium opacity-60 group-hover:opacity-100 transition-opacity">
                                   {isUp ? 'Select to Kill' : 'Select to Activate'}
                                </span>
                             </button>
                           )
                         })}
                      </div>
                   </div>

                   {/* Metadata Ring - Compact */}
                   <div className="mt-10 text-center">
                      <p className="text-[9px] font-bold text-apple-500 uppercase tracking-[0.2em] mb-8">Zab Metadata Cluster</p>
                      <div className="flex flex-wrap justify-center gap-4">
                         {[1, 2, 3].map(i => {
                            const nodeId = `metadata-${i}`;
                            const isUp = health.zabCluster?.nodesStatus?.[nodeId] === "UP";
                            const isLeader = health.zabCluster?.leader === nodeId;
                            return (
                              <div key={i} className={`p-5 rounded-apple-lg border transition-all duration-300 w-36 bg-white ${
                                isUp ? 'border-apple-100 shadow-sm' : 'border-apple-50 opacity-20 grayscale'
                              }`}>
                                 <div className={`w-8 h-8 mx-auto mb-4 rounded-full flex items-center justify-center ${isUp ? (isLeader ? 'bg-black text-white' : 'bg-apple-100 text-apple-900') : 'bg-apple-100'}`}>
                                    {isUp ? <CheckCircle size={16} strokeWidth={1.5} /> : <XCircle size={16} strokeWidth={1.5} />}
                                 </div>
                                 <span className="text-[9px] font-bold text-apple-500 tracking-widest">Metadata Node {i}</span>
                                 <p className="text-[12px] font-medium mt-1 leading-tight opacity-90">{isLeader ? 'Leader' : (isUp ? 'Follower' : 'Offline')}</p>
                              </div>
                            )
                         })}
                      </div>
                   </div>
                 </>
               ) : (
                 <div className="py-20 text-center animate-pulse">
                    <p className="text-apple-500 text-sm font-medium uppercase tracking-widest">Verifying Connection...</p>
                 </div>
               )}
            </div>
          )}
        </section>
      </main>

      {/* Custom Modal */}
      {activeModal && (
        <div className="fixed inset-0 bg-black/40 backdrop-blur-sm z-[100] flex items-center justify-center p-6 animate-in fade-in duration-300">
          <div className="bg-white rounded-apple-xl max-w-sm w-full shadow-2xl overflow-hidden animate-in zoom-in-95 duration-300 border border-apple-100">
            <div className="p-8 text-center border-b border-apple-100">
              <h3 className="text-xl font-bold text-apple-900 mb-3">{activeModal.title}</h3>
              <p className="text-apple-500 text-[14px] leading-relaxed font-medium">{activeModal.message}</p>
            </div>
            <div className="flex divide-x divide-apple-100 uppercase tracking-wide text-[14px] font-bold">
              {activeModal.type === 'confirm' ? (
                <>
                  <button 
                    onClick={() => setActiveModal(null)}
                    className="flex-1 py-5 hover:bg-apple-50 text-apple-400 transition-colors"
                  >
                    {activeModal.cancelLabel || 'Cancel'}
                  </button>
                  <button 
                    onClick={activeModal.onConfirm}
                    className={`flex-1 py-5 hover:bg-apple-50 transition-colors ${activeModal.isDestructive ? 'text-status-error' : 'text-apple-900'}`}
                  >
                    {activeModal.confirmLabel || 'OK'}
                  </button>
                </>
              ) : (
                <button 
                  onClick={() => setActiveModal(null)}
                  className="flex-1 py-5 hover:bg-apple-50 text-apple-900 transition-colors"
                >
                  {activeModal.confirmLabel || 'OK'}
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Success Notification */}
      {notification && (
        <div className="fixed bottom-10 left-1/2 -translate-x-1/2 z-[110] animate-in slide-in-from-bottom-10 fade-in duration-500">
          <div className="bg-apple-900 text-white px-8 py-3 rounded-full shadow-2xl flex items-center space-x-3 backdrop-blur-xl border border-white/10">
            {notification.type === 'success' ? <CheckCircle size={16} className="text-status-success" /> : <XCircle size={16} className="text-status-error" />}
            <span className="text-[13px] font-medium tracking-wide">{notification.message}</span>
          </div>
        </div>
      )}
    </div>
  );
}
