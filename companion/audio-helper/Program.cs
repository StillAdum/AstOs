using NAudio.Wave;
using NAudio.Dsp;
using System.Text.Json;
using InTheHand.Net;
using InTheHand.Net.Sockets;
if(args.Length>0 && args[0]=="--bluetooth-list") {
 try{using var client=new BluetoothClient();Console.WriteLine(JsonSerializer.Serialize(client.PairedDevices.Select(d=>new {address=d.DeviceAddress.ToString(),name=d.DeviceName})));}
 catch(Exception e){Console.Error.WriteLine("Bluetooth enumeration: "+e.Message);Environment.ExitCode=1;}return;
}
if(args.Length==2 && args[0]=="--bluetooth") {
 try{using var client=new BluetoothClient();await client.ConnectAsync(BluetoothAddress.Parse(args[1]),new Guid("00001101-0000-1000-8000-00805f9b34fb")).WaitAsync(TimeSpan.FromSeconds(12));using var stream=client.GetStream();Console.WriteLine("{\"transportReady\":true}");await Task.WhenAny(stream.CopyToAsync(Console.OpenStandardOutput()),Console.OpenStandardInput().CopyToAsync(stream));}
 catch(Exception e){Console.Error.WriteLine("Bluetooth RFCOMM: "+e.Message);Environment.ExitCode=1;}return;
}
// Opt-in WASAPI render-loopback analyzer. Outputs only nine normalized bands;
// PCM stays in memory and is never recorded, transmitted or written to disk.
try {
 using var capture = new WasapiLoopbackCapture();
 var buffer = new Complex[1024]; int used=0;long last=0;
 var format=capture.WaveFormat;int bytes=format.BitsPerSample/8,channels=format.Channels;
 bool floating=format.Encoding==WaveFormatEncoding.IeeeFloat || (format is WaveFormatExtensible ext && ext.SubFormat==new Guid("00000003-0000-0010-8000-00aa00389b71"));
 capture.DataAvailable += (_,e) => {
  for(int offset=0;offset+bytes*channels<=e.BytesRecorded;offset+=bytes*channels){
   double mono=0;for(int channel=0;channel<channels;channel++){int p=offset+channel*bytes;double v;
    if(floating&&bytes==4)v=BitConverter.ToSingle(e.Buffer,p);
    else if(bytes==2)v=BitConverter.ToInt16(e.Buffer,p)/32768.0;
    else if(bytes==4)v=BitConverter.ToInt32(e.Buffer,p)/2147483648.0;
    else if(bytes==3){int n=e.Buffer[p]|e.Buffer[p+1]<<8|e.Buffer[p+2]<<16;if((n&0x800000)!=0)n|=unchecked((int)0xff000000);v=n/8388608.0;}
    else v=0;mono+=v/channels;
   }
   buffer[used].X=(float)(mono*FastFourierTransform.HammingWindow(used,1024));buffer[used].Y=0;
   if(++used<1024)continue;used=0;if(Environment.TickCount64-last<80)continue;last=Environment.TickCount64;
   var fft=(Complex[])buffer.Clone();FastFourierTransform.FFT(true,10,fft);var bands=new double[9];
   for(int band=0;band<9;band++){int lo=Math.Max(1,(int)Math.Pow(2,band)),hi=Math.Min(512,(int)Math.Pow(2,band+1));double peak=0;for(int k=lo;k<hi;k++)peak=Math.Max(peak,Math.Sqrt(fft[k].X*fft[k].X+fft[k].Y*fft[k].Y));bands[band]=Math.Clamp(Math.Sqrt(peak)*5,0,1);}
   Console.WriteLine(JsonSerializer.Serialize(bands));
  }
 };
 capture.RecordingStopped += (_,e)=>{if(e.Exception!=null){Console.Error.WriteLine("Audio capture stopped: "+e.Exception.GetType().Name);Environment.Exit(1);}};
 capture.StartRecording();await Task.Delay(Timeout.Infinite);
} catch(Exception e){Console.Error.WriteLine("Audio capture unavailable: "+e.GetType().Name);Environment.ExitCode=1;}
