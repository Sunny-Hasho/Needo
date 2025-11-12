import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { HardDrive, Cloud, Server, UploadCloud, Trash2, Download, Activity, CheckCircle2, XCircle, File as FileIcon } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';

const API_URL = 'http://localhost:8080';

interface NodeHealth {
  upNodes: number;
  downNodesCount: number;
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
  version: number;
  timestamp: number;
  chunkCount: number;
}

export default function App() {
  const [activeTab, setActiveTab] = useState<'files' | 'dashboard'>('files');
  const [health, setHealth] = useState<ClusterHealth | null>(null);
  const [files, setFiles] = useState<Manifest[]>([]);
  const [uploading, setUploading] = useState(false);

  // Poll Health
  useEffect(() => {
    const fetchHealth = async () => {
      try {
        const res = await axios.get(`${API_URL}/health`);
        setHealth(res.data);
      } catch (e) {
        console.error("Failed to fetch health", e);
      }
    };
    fetchHealth();
    const interval = setInterval(fetchHealth, 3000);
    return () => clearInterval(interval);
  }, []);

  // Poll Files
  useEffect(() => {
    const fetchFiles = async () => {
      if (activeTab === 'files') {
        try {
          const res = await axios.get(`${API_URL}/files`);
          // res.data is an object with { filename: Manifest }
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

    setUploading(true);
    const formData = new FormData();
    formData.append('file', file);

    try {
      await axios.post(`${API_URL}/files`, formData);
      // Let the polling pick it up
    } catch (err) {
      alert("Failed to upload file");
      console.error(err);
    } finally {
      setUploading(false);
      e.target.value = ''; // Reset input
    }
  };

  const handleDownload = (filename: string) => {
    window.open(`${API_URL}/files/${filename}`, '_blank');
  };

  const handleDelete = async (filename: string) => {
    if (!confirm(`Are you sure you want to delete ${filename}?`)) return;
    try {
      await axios.delete(`${API_URL}/files/${filename}`);
      // Let the polling pick it up to refresh the UI
    } catch (e) {
      alert("Failed to delete. Ensure ZAB metadata nodes are healthy.");
    }
  };

  return (
    <div className="flex h-screen bg-gray-50 text-gray-900 font-sans">
      {/* Sidebar */}
      <div className="w-64 bg-white border-r border-gray-200 flex flex-col">
        <div className="p-6 flex items-center space-x-3 text-brand-600">
          <Cloud className="w-8 h-8" />
          <span className="text-xl font-bold tracking-tight">ColieeDrive</span>
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

        {/* Storage Quick Stats in Bottom Sidebar */}
        {health && (
          <div className="p-6 border-t border-gray-100">
            <div className="flex items-center justify-between text-sm mb-2">
              <span className="text-gray-500 font-medium">Nodes Healthy</span>
              <span className="text-brand-600 font-bold">{health.storage.upNodes}/{health.storage.totalNodes}</span>
            </div>
            <div className="w-full bg-gray-200 rounded-full h-2">
              <div 
                className="bg-brand-500 h-2 rounded-full" 
                style={{ width: `${(health.storage.upNodes / health.storage.totalNodes) * 100}%` }}
              ></div>
            </div>
          </div>
        )}
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-auto">
        <header className="bg-white border-b border-gray-200 px-8 py-5 flex items-center justify-between sticky top-0 z-10">
          <h1 className="text-2xl font-bold text-gray-800 tracking-tight">
            {activeTab === 'files' ? 'My Files' : 'System Dashboard'}
          </h1>
          {activeTab === 'files' && (
            <div className="relative">
              <input 
                type="file" 
                id="file-upload" 
                className="hidden" 
                onChange={handleUpload} 
                disabled={uploading}
              />
              <label 
                htmlFor="file-upload" 
                className={`flex items-center space-x-2 px-6 py-2.5 rounded-full font-medium cursor-pointer transition-colors shadow-sm
                  ${uploading ? 'bg-gray-100 text-gray-400' : 'bg-brand-600 text-white hover:bg-brand-700'}`}
              >
                <UploadCloud className="w-5 h-5" />
                <span>{uploading ? 'Uploading...' : 'Upload File'}</span>
              </label>
            </div>
          )}
        </header>

        <main className="p-8 max-w-6xl mx-auto">
          {activeTab === 'files' ? (
            <div className="bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="bg-gray-50/50 border-b border-gray-100 text-sm font-medium text-gray-500">
                    <th className="py-4 px-6">Name</th>
                    <th className="py-4 px-6">Chunks</th>
                    <th className="py-4 px-6">Uploaded</th>
                    <th className="py-4 px-6 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {files.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-12 text-center text-gray-400">
                        <UploadCloud className="w-12 h-12 mx-auto mb-3 opacity-20" />
                        <p>No files uploaded yet. Drag or click Upload above.</p>
                      </td>
                    </tr>
                  ) : files.map((f) => (
                    <tr key={f.fileId} className="hover:bg-gray-50/50 transition-colors group">
                      <td className="py-4 px-6 font-medium text-gray-800 flex items-center space-x-3">
                        <FileIcon className="w-5 h-5 text-brand-500" />
                        <span>{f.fileId}</span>
                      </td>
                      <td className="py-4 px-6 text-gray-600">
                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700">
                          {f.chunkCount} parts
                        </span>
                      </td>
                      <td className="py-4 px-6 text-gray-500 text-sm">
                        {formatDistanceToNow(new Date(f.timestamp), { addSuffix: true })}
                      </td>
                      <td className="py-4 px-6 text-right space-x-2">
                        <button 
                          onClick={() => handleDownload(f.fileId)}
                          className="p-2 text-gray-400 hover:text-brand-600 hover:bg-brand-50 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                        >
                          <Download className="w-5 h-5" />
                        </button>
                        <button 
                          onClick={() => handleDelete(f.fileId)}
                          className="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                        >
                          <Trash2 className="w-5 h-5" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="space-y-6">
              {!health ? (
                <div className="animate-pulse flex space-x-4">
                  <div className="flex-1 space-y-6 py-1">
                    <div className="h-2 bg-gray-200 rounded"></div>
                    <div className="space-y-3">
                      <div className="grid grid-cols-3 gap-4">
                        <div className="h-2 bg-gray-200 rounded col-span-2"></div>
                        <div className="h-2 bg-gray-200 rounded col-span-1"></div>
                      </div>
                      <div className="h-2 bg-gray-200 rounded"></div>
                    </div>
                  </div>
                </div>
              ) : (
                <>
                  {/* Overview Cards */}
                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                    <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex items-start space-x-4">
                      <div className="p-3 bg-brand-50 text-brand-600 rounded-xl">
                        <Server className="w-6 h-6" />
                      </div>
                      <div>
                        <p className="text-sm font-medium text-gray-500">Storage Nodes</p>
                        <h3 className="text-2xl font-bold tracking-tight text-gray-900 mt-1">
                          {health.storage.upNodes} <span className="text-gray-400 text-lg font-medium">/ {health.storage.totalNodes} UP</span>
                        </h3>
                      </div>
                    </div>
                    
                    <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex items-start space-x-4">
                      <div className="p-3 bg-blue-50 text-blue-600 rounded-xl">
                        <Activity className="w-6 h-6" />
                      </div>
                      <div>
                        <p className="text-sm font-medium text-gray-500">Quorum Setting</p>
                        <h3 className="text-2xl font-bold tracking-tight text-gray-900 mt-1">
                          W={health.storage.writeQuorum} <span className="text-gray-400 font-medium">·</span> R={health.storage.readQuorum}
                        </h3>
                      </div>
                    </div>

                    <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex items-start space-x-4">
                      <div className="p-3 bg-purple-50 text-purple-600 rounded-xl">
                        <Cloud className="w-6 h-6" />
                      </div>
                      <div>
                        <p className="text-sm font-medium text-gray-500">ZAB Leader</p>
                        <h3 className="text-2xl font-bold tracking-tight text-gray-900 mt-1 capitalize text-purple-700">
                          {health.zabCluster.leader || "ELECTION..."}
                        </h3>
                      </div>
                    </div>
                  </div>

                  {/* Node Detailed List */}
                  <div className="mt-8">
                    <h2 className="text-lg font-bold text-gray-800 mb-4">Storage Layer Topology</h2>
                    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
                      {[1, 2, 3, 4].map((i) => {
                        const nodeUrl = `http://localhost:900${i}`;
                        const isUp = !health.storage.downNodeUrls?.includes(nodeUrl);
                        return (
                          <div key={i} className={`p-5 rounded-xl border ${isUp ? 'bg-white border-brand-100' : 'bg-red-50 border-red-100'}`}>
                            <div className="flex justify-between items-start mb-2">
                              <span className="font-medium text-gray-700">Storage Node {i}</span>
                              {isUp ? (
                                <CheckCircle2 className="w-5 h-5 text-brand-500" />
                              ) : (
                                <XCircle className="w-5 h-5 text-red-500" />
                              )}
                            </div>
                            <div className="text-sm text-gray-500">Port: 900{i}</div>
                            <div className={`mt-3 inline-flex px-2 py-1 text-xs font-bold rounded ${isUp ? 'bg-brand-50 text-brand-700' : 'bg-red-100 text-red-700'}`}>
                              {isUp ? 'ONLINE' : 'OFFLINE'}
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  </div>

                  {/* ZAB Detailed List */}
                  <div className="mt-8">
                    <h2 className="text-lg font-bold text-gray-800 mb-4">ZAB Metadata Topology</h2>
                    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                      {[1, 2, 3].map((i) => {
                        const nodeId = `metadata-${i}`;
                        const isUp = health.zabCluster?.nodesStatus?.[nodeId] === "UP";
                        const isLeader = health.zabCluster?.leader === nodeId;
                        
                        return (
                          <div key={i} className={`p-5 rounded-xl border ${isUp ? (isLeader ? 'bg-purple-50 border-purple-200' : 'bg-white border-brand-100') : 'bg-red-50 border-red-100'}`}>
                            <div className="flex justify-between items-start mb-2">
                              <span className="font-medium text-gray-700 flex items-center space-x-2">
                                <span>ZAB Node {i}</span>
                                {isLeader && <span className="px-2 py-0.5 text-[10px] uppercase font-bold tracking-wider bg-purple-200 text-purple-800 rounded-full">LEADER</span>}
                              </span>
                              {isUp ? (
                                <CheckCircle2 className={`w-5 h-5 ${isLeader ? 'text-purple-500' : 'text-brand-500'}`} />
                              ) : (
                                <XCircle className="w-5 h-5 text-red-500" />
                              )}
                            </div>
                            <div className="text-sm text-gray-500">Port: 808{i}</div>
                            <div className={`mt-3 inline-flex px-2 py-1 text-xs font-bold rounded ${isUp ? 'bg-brand-50 text-brand-700' : 'bg-red-100 text-red-700'}`}>
                              {isUp ? 'ONLINE' : 'OFFLINE'}
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
