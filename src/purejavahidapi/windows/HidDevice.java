/*
 * Copyright (c) 2014, Kustaa Nyholm / SpareTimeLabs
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, 
 * are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list 
 * of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice, this 
 * list of conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 *  
 * Neither the name of the Kustaa Nyholm or SpareTimeLabs nor the names of its 
 * contributors may be used to endorse or promote products derived from this software 
 * without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, 
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT 
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, 
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
package purejavahidapi.windows;

import static purejavahidapi.windows.HidLibrary.*;
import static purejavahidapi.windows.Kernel32Library.*;
import static purejavahidapi.windows.SetupApiLibrary.*;

import java.nio.ByteBuffer;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import purejavahidapi.DeviceRemovalListener;
import purejavahidapi.InputReportListener;
import purejavahidapi.linux.UdevLibrary;
import purejavahidapi.shared.Frontend;
import purejavahidapi.shared.SyncPoint;
import purejavahidapi.windows.HidLibrary.HIDD_ATTRIBUTES;
import purejavahidapi.windows.HidLibrary.HIDP_CAPS;
import purejavahidapi.windows.WinDef.HANDLE;
import purejavahidapi.windows.WinDef.OVERLAPPED;

public class HidDevice implements purejavahidapi.HidDevice {
	private boolean m_Open = true;
	private Frontend m_Frontend;
	private HANDLE m_Handle;
	private int m_OutputReportLength;
	private Memory m_OutputReportMemory;
	private OVERLAPPED m_OutputReportOverlapped;
	private int[] m_OutputReportBytesWritten;
	private int m_InputReportLength;
	private OVERLAPPED m_InputReportOverlapped = new OVERLAPPED();
	private Memory m_InputReportMemory;
	private byte[] m_InputReportBytes;
	private int[] m_InputReportBytesRead = { 0 };
	private Thread m_Thread;
	private SyncPoint m_SyncStart;
	private SyncPoint m_SyncShutdown;
	private boolean m_StopThread;
	private HidDeviceInfo m_HidDeviceInfo;
	private InputReportListener m_InputReportListener;
	private DeviceRemovalListener m_DeviceRemovalListener;

	/* package */HidDevice(String path, HANDLE handle, Frontend frontend) {
		m_Frontend = frontend;
		m_Handle = handle;
		HIDD_ATTRIBUTES attrib = new HIDD_ATTRIBUTES();
		attrib.Size = new NativeLong(attrib.size());
		HidD_GetAttributes(handle, attrib);
		m_HidDeviceInfo = new HidDeviceInfo(path, handle, attrib);
		boolean res;
		HIDP_PREPARSED_DATA[] ppd = new HIDP_PREPARSED_DATA[1];
		res = HidD_GetPreparsedData(handle, ppd);
		if (!res) {
			CloseHandle(handle);
			return;
		}
		HIDP_CAPS caps = new HIDP_CAPS();
		int nt_res = HidP_GetCaps(ppd[0], caps);
		if (nt_res != HIDP_STATUS_SUCCESS) {
			CloseHandle(handle);
			return;
		}
		m_OutputReportLength = caps.OutputReportByteLength;
		if (m_OutputReportLength > 0)
			m_OutputReportMemory = new Memory(m_OutputReportLength);
		m_OutputReportOverlapped = new OVERLAPPED();
		m_OutputReportBytesWritten = new int[] { 0 };

		m_InputReportLength = caps.InputReportByteLength;
		m_InputReportOverlapped = new OVERLAPPED();
		if (m_InputReportLength > 0) {
			m_InputReportMemory = new Memory(m_InputReportLength);
			m_InputReportBytes = new byte[m_InputReportLength];
		}
		m_InputReportBytesRead = new int[] { 0 };

		HidD_FreePreparsedData(ppd[0]);

		m_SyncStart = new SyncPoint(2);
		m_SyncShutdown = new SyncPoint(2);

		m_Thread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					runReadOnBackground();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, m_HidDeviceInfo.getPath());
		if (m_InputReportLength > 0) {
			m_Thread.start();
			m_SyncStart.waitAndSync();
		}

	}

	@Override
	synchronized public void close() {
		if (!m_Open)
			throw new IllegalStateException("device not open");

		m_StopThread = true;
		if (m_InputReportLength > 0) {
			m_Thread.interrupt();
			m_SyncShutdown.waitAndSync();
		}
		CloseHandle(m_Handle);
		m_Frontend.closeDevice(this);
		m_Open = false;
	}

	@Override
	synchronized public int setOutputReport(byte reportID, byte[] data, int length) {
		if (!m_Open)
			throw new IllegalStateException("device not open");
		if (m_OutputReportLength == 0)
			throw new IllegalArgumentException("this device supportst no output reports");
		// In Windows writeFile() to HID device data has to be preceded with the report number, regardless 
		m_OutputReportMemory.write(0, new byte[] { reportID }, 0, 1);
		m_OutputReportMemory.write(1, data, 0, length);
		// In windows always attempt to write as many bytes as there are in the longest report plus one for the report number (even if zero ie not used)
		if (!WriteFile(m_Handle, m_OutputReportMemory, m_OutputReportLength, null, m_OutputReportOverlapped)) {
			if (GetLastError() != ERROR_IO_PENDING) {
				// WriteFile() failed. Return error.
				//register_error(dev, "WriteFile");
				return -1;
			}
		}

		if (!GetOverlappedResult(m_Handle, m_OutputReportOverlapped, m_OutputReportBytesWritten, true/* wait */)) {
			// The Write operation failed.
			//register_error(dev, "WriteFile");
			return 0;
		}

		return m_OutputReportBytesWritten[0] - 1;
	}

	@Override
	synchronized public int setFeatureReport(byte[] data, int length) {
		if (!m_Open)
			throw new IllegalStateException("device not open");
		if (!HidD_SetFeature(m_Handle, data, length)) {
			//register_error(dev, "HidD_SetFeature");
			return -1;
		}

		return length;
	}

	@Override
	synchronized public void setInputReportListener(InputReportListener listener) {
		if (!m_Open)
			throw new IllegalStateException("device not open");
		m_InputReportListener = listener;
	}

	@Override
	synchronized public void setDeviceRemovalListener(DeviceRemovalListener listener) {
		if (!m_Open)
			throw new IllegalStateException("device not open");
		m_DeviceRemovalListener = listener;
	}

	@Override
	synchronized public int getFeatureReport(byte[] data, int length) {
		if (!m_Open)
			throw new IllegalStateException("device not open");
		if (false) { // can't use this as it will not return the size of the report
			if (!HidD_GetFeature(m_Handle, data, length)) {
				//register_error(dev, "HidD_SetFeature");
				System.out.println(GetLastError());
				return -1;
			}
		} else { 
			int[] bytes = { 0 };

			OVERLAPPED ol = new OVERLAPPED();
			Pointer buffer = new Memory(data.length);
			if (!DeviceIoControl(m_Handle, IOCTL_HID_GET_FEATURE, buffer, length, buffer, length, bytes, ol)) {
				if (GetLastError() != ERROR_IO_PENDING)
					return -1;
			}

			if (!GetOverlappedResult(m_Handle, ol, bytes, true/* wait */))
				return -1;
			int n = bytes[0];
			byte[] t=buffer.getByteArray(0, n);
			System.arraycopy(t, 0, data,0,n);
			return n;
		}
		return -1; // Eclipse says this is unreachable (it is), but won't compile without it ... go figure

	}

	@Override
	synchronized public HidDeviceInfo getHidDeviceInfo() {
		if (!m_Open)
			throw new IllegalStateException("device not open");
		return m_HidDeviceInfo;
	}

	private void runReadOnBackground() {
		m_SyncStart.waitAndSync();
		while (!m_StopThread) {
			m_InputReportBytesRead[0] = 0;
			ResetEvent(m_InputReportOverlapped.hEvent);

			// In Windos ReadFile() from a HID device Windows expects us to attempt to read as much bytes as there are
			// in the longest report plus one for the report number (even if not used) and the data is always
			// preceded with the report number (even if not used in case of which it is zero)
			if (!ReadFile(m_Handle, m_InputReportMemory, m_InputReportLength, null, m_InputReportOverlapped)) {
				if (GetLastError() == ERROR_DEVICE_NOT_CONNECTED)
					break; // early exit if the device disappears
				if (GetLastError() != ERROR_IO_PENDING) {
					CancelIo(m_Handle);
					System.out.println("ReadFile failed with GetLastError()==" + GetLastError());
				}
			}

			if (!GetOverlappedResult(m_Handle, m_InputReportOverlapped, m_InputReportBytesRead, true/* wait */)) {
				if (GetLastError() == ERROR_DEVICE_NOT_CONNECTED)
					break; // early exit if the device disappears
				System.out.println("GetOverlappedResult failed with GetLastError()==" + GetLastError());
			}

			if (m_InputReportBytesRead[0] > 0) {
				byte reportID = m_InputReportMemory.getByte(0);
				int offs = 0;
				if (reportID == 0x00) {
					m_InputReportBytesRead[0]--;
					offs = 1;
				}
				m_InputReportMemory.read(offs, m_InputReportBytes, 0, m_InputReportBytesRead[0]);

				if (m_InputReportListener != null)
					m_InputReportListener.onInputReport(this, reportID, m_InputReportBytes, m_InputReportBytesRead[0]);
			}

		}
		m_SyncShutdown.waitAndSync();
	}

	/* package */DeviceRemovalListener getDeviceRemovalListener() {
		return m_DeviceRemovalListener;
	}
}
