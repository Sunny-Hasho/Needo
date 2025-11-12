import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { HardDrive, Cloud, Server, UploadCloud, Trash2, Download, Activity, CheckCircle2, XCircle, File as FileIcon, Box, X } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';

const API_URL = 'http://localhost:8080';

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
  replicas: Record<string, string[]>; // chunkId -> list of node URLs
  version: number;
  timestamp: number;
  chunkCount: number;
}

export default function App() {
  const [activeTab, setActiveTab] = useState<'files' | 'dashboard'>('files');
  const [health, setHealth] = useState<ClusterHealth | null>(null);
  const [files, setFiles] = useState<Manifest[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const [selectedFileId, setSelectedFileId] = useState<string | null>(null);

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
    } catch (err) {
      alert("Failed to upload file");
      console.error(err);
    } finally {
      setIsUploading(false);
      if (e.target) e.target.value = '';
    }
  };

  const handleDownload = (filename: string) => {
    window.open(`${API_URL}/files/${filename}`, '_blank');
  };

  const handleDelete = async (filename: string) => {
    if (!confirm(`Are you sure you want to delete ${filename}?`)) return;
    try {
      await axios.delete(`${API_URL}/files/${filename}`);
    } catch (e) {
      alert("Failed to delete. Ensure ZAB metadata nodes are healthy.");
    }
  };

  const handleKillNode = async (url: string) => {
    const port = url.split(':').pop();
    if (!confirm(`⚠️ WARNING: This will immediately kill the Java process on port ${port} to test fault-tolerance. Proceed?`)) return;
    
    try {
      // Direct call to the node's chaos endpoint
      await axios.post(`${url}/chaos/kill`);
      alert(`Chaos sequence initiated for Node on port ${port}. It will shut down in 1 second.`);
    } catch (e) {
      console.error("Failed to kill node", e);
      alert("Node is already offline or unreachable.");
    }
  };

  return (
    <div className="flex h-screen bg-gray-50 text-gray-900 font-sans">
      {/* Sidebar */}
      <div className="w-64 bg-white border-r border-gray-200 flex flex-col">
        <div className="p-6 flex items-center space-x-3 text-brand-600">
          <Cloud className="w-8 h-8" />
          <span className="text-xl font-bold tracking-tight">NeedoDrive</span>
        </div>
        
        <nav className="flex-1 px-4 space-y-2 mt-4">
          <button 
            onClick={() => setActiveTab('files')}
            className={`flex items-center space-x-3 w-full px-4 py-3 rounded-xl font-medium transition-colors ${
              activeTab === 'files' ? 'bg-brand-50 text-brand-600' : 'text-gray-600 hover:bg-gray-100'
            }`}
          >
            <HardDrive className="w-5 h-5" />
            <span>My Files</span>
          </button>
          <button 
            onClick={() => setActiveTab('dashboard')}
            className={`flex items-center space-x-3 w-full px-4 py-3 rounded-xl font-medium transition-colors ${
              activeTab === 'dashboard' ? 'bg-brand-50 text-brand-600' : 'text-gray-600 hover:bg-gray-100'
            }`}
          >
            <Activity className="w-5 h-5" />
            <span>Cluster Dashboard</span>
          </button>
        </nav>

        {/* Global Progress Tracking */}
        {health && (
          <div className="p-6 border-t border-gray-100">
            <div className="flex items-center justify-between text-sm mb-2">
              <span className="text-gray-500 font-medium tracking-tight">Storage Health</span>
              <span className="text-brand-600 font-bold">{health.storage.upNodesCount}/{health.storage.totalNodes}</span>
            </div>
            <div className="w-full bg-gray-200 rounded-full h-1.5 overflow-hidden">
              <div 
                className={`h-full transition-all duration-500 ${health.storage.upNodesCount < health.storage.totalNodes ? 'bg-orange-500' : 'bg-brand-500'}`}
                style={{ width: `${(health.storage.upNodesCount / health.storage.totalNodes) * 100}%` }}
              ></div>
            </div>
            <div className="mt-3 flex items-center space-x-2">
              <div className={`w-2 h-2 rounded-full animate-pulse ${health.storage.upNodesCount < 2 ? 'bg-red-500' : 'bg-green-500'}`}></div>
              <span className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">
                {health.storage.upNodesCount < health.storage.writeQuorum ? 'QUORUM LOSS POSSIBLE' : 'SYSTEM HEALTHY'}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-auto flex flex-col">
        <header className="bg-white border-b border-gray-200 px-8 py-5 flex items-center justify-between sticky top-0 z-10 shrink-0">
          <h1 className="text-2xl font-black text-gray-800 tracking-tighter uppercase">
            {activeTab === 'files' ? 'Archive' : 'Pulse Monitor'}
          </h1>
          <div className="flex items-center space-x-4">
            {health && (
              <div className="px-4 py-1.5 bg-gray-100 rounded-full text-xs font-mono text-gray-500 flex items-center space-x-2">
                <span className="w-2 h-2 bg-blue-400 rounded-full animate-ping"></span>
                <span>LAMPORT CLOCK: {health.lamportClock}</span>
              </div>
            )}
            {activeTab === 'files' && (
              <div className="relative">
                <input 
                  type="file" 
                  id="file-upload" 
                  className="hidden" 
                  onChange={handleUpload} 
                  disabled={isUploading}
                />
                <label 
                  htmlFor="file-upload" 
                  className={`flex items-center space-x-2 px-6 py-2.5 rounded-full font-bold cursor-pointer transition-all shadow-lg active:scale-95
                    ${isUploading ? 'bg-gray-100 text-gray-400' : 'bg-black text-white hover:bg-gray-800'}`}
                >
                  <UploadCloud className="w-5 h-5" />
                  <span>{isUploading ? 'UPLOADING...' : 'UPLOAD'}</span>
                </label>
              </div>
            )}
          </div>
        </header>

        <main className="p-8 flex-1">
          {activeTab === 'files' ? (
            <div className="space-y-6">
              <div className="bg-white rounded-2xl shadow-xl border border-gray-100 overflow-hidden">
                <table className="w-full text-left border-collapse">
                  <thead>
                    <tr className="bg-gray-900 border-b border-gray-800 text-[11px] font-black text-gray-400 uppercase tracking-widest">
                      <th className="py-4 px-6">Identifer</th>
                      <th className="py-4 px-6 text-center">Replication Status</th>
                      <th className="py-4 px-6">Timestamp</th>
                      <th className="py-4 px-6 text-right">Control</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-50">
                    {files.length === 0 ? (
                      <tr>
                        <td colSpan={4} className="py-24 text-center text-gray-300">
                          <Cloud className="w-16 h-16 mx-auto mb-4 opacity-10" />
                          <p className="font-bold uppercase tracking-widest text-sm">Cluster Vault Empty</p>
                        </td>
                      </tr>
                    ) : files.map((f) => (
                      <tr key={f.fileId} className="hover:bg-gray-50/50 transition-colors group">
                        <td className="py-5 px-6 font-bold text-gray-800 flex items-center space-x-3">
                          <div className="p-2 bg-brand-50 rounded-lg text-brand-600">
                            <FileIcon className="w-5 h-5" />
                          </div>
                          <span className="truncate max-w-[200px]">{f.fileId}</span>
                        </td>
                        <td className="py-5 px-6 text-center">
                          <button 
                            onClick={() => setSelectedFileId(f.fileId)}
                            className="inline-flex items-center space-x-2 px-3 py-1 rounded-full text-[10px] font-black uppercase tracking-tighter bg-blue-50 text-blue-600 hover:bg-blue-100 transition-colors"
                          >
                            <Server className="w-3 h-3" />
                            <span>{f.chunkCount} Chunks (N=3)</span>
                          </button>
                        </td>
                        <td className="py-5 px-6 text-gray-500 text-xs font-medium">
                          {formatDistanceToNow(new Date(f.timestamp), { addSuffix: true })}
                        </td>
                        <td className="py-5 px-6 text-right space-x-2">
                          <button 
                            onClick={() => handleDownload(f.fileId)}
                            className="p-2.5 text-gray-400 hover:text-black hover:bg-gray-100 rounded-xl transition-all"
                            title="Download"
                          >
                            <Download className="w-5 h-5" />
                          </button>
                          <button 
                            onClick={() => handleDelete(f.fileId)}
                            className="p-2.5 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-xl transition-all"
                            title="Delete"
                          >
                            <Trash2 className="w-5 h-5" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Chunk Mapping Modal */}
              {selectedFileId && (
                <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
                  <div className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-2xl shadow-2xl overflow-hidden animate-in fade-in zoom-in duration-300">
                    {(() => {
                      const file = files.find(f => f.fileId === selectedFileId);
                      if (!file) return null;
                      return (
                        <>
                          <div className="p-6 border-b border-slate-800 flex justify-between items-center bg-slate-800/50">
                            <div>
                              <h3 className="text-xl font-bold text-white flex items-center gap-2">
                                <Box className="w-5 h-5 text-indigo-400" />
                                Data Distribution: {file.fileId}
                              </h3>
                              <p className="text-slate-400 text-sm mt-1">Physical chunk mapping across cluster</p>
                            </div>
                            <button 
                              onClick={() => setSelectedFileId(null)}
                              className="p-2 hover:bg-slate-700 rounded-full text-slate-400 hover:text-white transition-colors"
                            >
                              <X className="w-6 h-6" />
                            </button>
                          </div>
                          <div className="p-6 max-h-[60vh] overflow-y-auto custom-scrollbar">
                            <div className="space-y-4">
                              {file.chunkIds.map((chunkId, idx) => (
                                <div key={chunkId} className="bg-slate-800/50 border border-slate-700 rounded-xl p-4 flex flex-col gap-3">
                                  <div className="flex justify-between items-center">
                                    <span className="text-xs font-mono text-indigo-300 bg-indigo-500/10 px-2 py-1 rounded">
                                      CHUNK_{idx + 1}
                                    </span>
                                    <span className="text-[10px] text-slate-500 font-mono">{chunkId}</span>
                                  </div>
                                  <div className="flex flex-wrap gap-2">
                                    {file.replicas[chunkId]?.map(nodeUrl => {
                                      const nodeNum = nodeUrl.split(':').pop();
                                      const isDown = health?.storage.downNodeUrls.includes(nodeUrl);
                                      return (
                                        <div 
                                          key={nodeUrl} 
                                          className={`flex items-center gap-2 px-3 py-1.5 rounded-lg border text-xs font-medium ${
                                            isDown 
                                              ? 'bg-red-500/10 border-red-500/30 text-red-400' 
                                              : 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
                                          }`}
                                        >
                                          <Server className="w-3 h-3" />
                                          Node: {nodeNum}
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>
                          <div className="p-4 bg-slate-800/30 border-t border-slate-800 flex justify-end">
                            <button 
                              onClick={() => setSelectedFileId(null)}
                              className="px-6 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-xl transition-all font-medium"
                            >
                              Close
                            </button>
                          </div>
                        </>
                      );
                    })()}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="space-y-12">
              {!health ? (
                 <div className="flex items-center justify-center py-20 text-gray-300 animate-pulse font-black uppercase tracking-widest">Syncing with Cluster...</div>
              ) : (
                <>
                  {/* Overview Cards */}
                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
                    <div className="bg-white p-8 rounded-3xl shadow-xl border border-gray-100 relative overflow-hidden group">
                      <div className="absolute top-0 right-0 w-32 h-32 bg-brand-50 rounded-bl-full -mr-16 -mt-16 opacity-50 transition-transform group-hover:scale-110"></div>
                      <Server className="w-8 h-8 text-brand-600 mb-4" />
                      <p className="text-[10px] font-black text-gray-400 uppercase tracking-widest">Storage Status</p>
                      <h3 className="text-4xl font-black tracking-tighter text-gray-900 mt-1">
                        {health.storage.upNodesCount} <span className="text-gray-300 text-2xl">/ {health.storage.totalNodes}</span>
                      </h3>
                      <div className="mt-4 flex items-center space-x-2 text-[10px] font-bold text-brand-600 bg-brand-50 px-3 py-1 rounded-full w-fit">
                        <CheckCircle2 className="w-3 h-3" />
                        <span>QUORUM SAFE</span>
                      </div>
                    </div>
                    
                    <div className="bg-white p-8 rounded-3xl shadow-xl border border-gray-100 relative overflow-hidden group">
                      <div className="absolute top-0 right-0 w-32 h-32 bg-blue-50 rounded-bl-full -mr-16 -mt-16 opacity-50 transition-transform group-hover:scale-110"></div>
                      <Activity className="w-8 h-8 text-blue-600 mb-4" />
                      <p className="text-[10px] font-black text-gray-400 uppercase tracking-widest">Consistency Quorum</p>
                      <h3 className="text-4xl font-black tracking-tighter text-gray-900 mt-1">
                        W={health.storage.writeQuorum} <span className="text-gray-300">|</span> R={health.storage.readQuorum}
                      </h3>
                      <div className="mt-4 flex items-center space-x-2 text-[10px] font-bold text-blue-600 bg-blue-50 px-3 py-1 rounded-full w-fit">
                        <Activity className="w-3 h-3" />
                        <span>STRONG CONSISTENCY (W+R &gt; N)</span>
                      </div>
                    </div>

                    <div className="bg-white p-8 rounded-3xl shadow-xl border border-gray-100 relative overflow-hidden group">
                      <div className="absolute top-0 right-0 w-32 h-32 bg-purple-50 rounded-bl-full -mr-16 -mt-16 opacity-50 transition-transform group-hover:scale-110"></div>
                      <Cloud className="w-8 h-8 text-purple-600 mb-4" />
                      <p className="text-[10px] font-black text-gray-400 uppercase tracking-widest">Consensus Leader</p>
                      <h3 className="text-4xl font-black tracking-tighter text-purple-700 mt-1 uppercase">
                        {health.zabCluster.leader ? health.zabCluster.leader.split('-').pop() : "NONE"}
                      </h3>
                      <div className="mt-4 flex items-center space-x-2 text-[10px] font-bold text-purple-600 bg-purple-50 px-3 py-1 rounded-full w-fit uppercase">
                        <CheckCircle2 className="w-3 h-3" />
                        <span>ZAB PROTOCOL ACTIVE</span>
                      </div>
                    </div>
                  </div>

                  {/* CHAOS CONTROLS */}
                  <div className="mt-12 bg-gray-900 rounded-[2.5rem] p-12 text-white shadow-2xl">
                    <div className="flex items-center justify-between mb-8">
                      <div>
                        <h2 className="text-3xl font-black tracking-tight uppercase italic">Topology & Chaos</h2>
                        <p className="text-gray-400 font-medium text-sm">Force server crashes to test self-healing re-replication.</p>
                      </div>
                      <div className="flex items-center space-x-2 px-6 py-2 bg-red-500/10 border border-red-500/20 rounded-full text-red-500 text-[10px] font-black uppercase tracking-widest animate-pulse">
                        <span>Fault-Tolerance Testing Active</span>
                      </div>
                    </div>
                    
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                      {[9001, 9002, 9003, 9004].map((port) => {
                        const url = `http://localhost:${port}`;
                        const isUp = health.storage.upNodeUrls?.includes(url);
                        return (
                          <div key={port} className={`p-8 rounded-[2rem] transition-all duration-300 border-2 ${
                            isUp ? 'bg-gray-800/50 border-gray-700 hover:border-brand-500 group' : 'bg-red-950/20 border-red-900/50 grayscale'
                          }`}>
                            <div className="flex justify-between items-start mb-6">
                              <span className="text-xs font-black uppercase tracking-widest text-gray-500">Node {port}</span>
                              <div className={`w-3 h-3 rounded-full ${isUp ? 'bg-green-500 shadow-[0_0_15px_rgba(34,197,94,0.5)]' : 'bg-red-500'}`}></div>
                            </div>
                            <h4 className={`text-2xl font-black tracking-tighter mb-8 ${isUp ? 'text-white' : 'text-gray-600 italic'}`}>
                              {isUp ? 'OPERATIONAL' : 'OFFLINE'}
                            </h4>
                            {isUp && (
                              <button 
                                onClick={() => handleKillNode(url)}
                                className="w-full py-4 text-center rounded-2xl bg-white/5 border border-white/10 hover:bg-red-500 hover:text-white transition-all text-[10px] font-black uppercase tracking-widest"
                              >
                                Trigger Crash
                              </button>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  {/* ZAB Detailed List */}
                  <div className="mt-12">
                    <h2 className="text-xl font-black text-gray-800 mb-6 uppercase tracking-tight italic text-right">ZAB Consensus Ring</h2>
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                      {[1, 2, 3].map((i) => {
                        const nodeId = `metadata-${i}`;
                        const isUp = health.zabCluster?.nodesStatus?.[nodeId] === "UP";
                        const isLeader = health.zabCluster?.leader === nodeId;
                        
                        return (
                          <div key={i} className={`p-8 rounded-[2rem] border-2 transition-all ${
                            isUp ? (isLeader ? 'bg-purple-50 border-purple-200' : 'bg-white border-gray-100') : 'bg-red-50 border-red-100 grayscale'
                          }`}>
                            <div className="flex justify-between items-start mb-6">
                              <span className="font-bold text-gray-800 flex items-center space-x-2">
                                <span className="uppercase text-xs tracking-widest text-gray-400">NODE {i}</span>
                                {isLeader && <span className="px-3 py-1 text-[8px] font-black bg-purple-600 text-white rounded-full tracking-widest">LEADER</span>}
                              </span>
                              {isUp ? (
                                <CheckCircle2 className={`w-6 h-6 ${isLeader ? 'text-purple-600' : 'text-brand-600'}`} />
                              ) : (
                                <XCircle className="w-6 h-6 text-red-500" />
                              )}
                            </div>
                            <div className="text-sm font-bold text-gray-400 uppercase tracking-tighter">Port 808{i}</div>
                            <div className="mt-6 flex items-center space-x-2">
                              <div className={`w-2 h-2 rounded-full ${isUp ? 'bg-green-500' : 'bg-red-400'}`}></div>
                              <span className="text-[10px] font-black uppercase tracking-widest text-gray-500">{isUp ? 'SYNCED' : 'PARTITIONED'}</span>
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  </div>

                </>
              )}
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

