package control;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;


public class JOCLRenderizador implements BufferedImageOp {
	// bota aqui o dir do cod opencl
	private static final String KERNEL_SOURCE_FILE_NAME =  JOCLRenderizador.class.getResource("codigoOpenCL.cl").getPath();
  
	/**
	 * Compute the value which is the smallest multiple of the given group size
	 * that is greater than or equal to the given global size.
	 * 
	 * @param groupSize
	 *            The group size
	 * @param globalSize
	 *            The global size
	 * @return The rounded global size
	 */
	private static long round(long groupSize, long globalSize) {
		long r = globalSize % groupSize;
		if (r == 0) {
			return globalSize;
		} else {
			return globalSize + groupSize - r;
		}
	}

	/**
	 * Helper function which reads the file with the given name and returns the
	 * contents of this file as a String. Will exit the application if the file
	 * can not be read.
	 * 
	 * @param fileName
	 *            The name of the file to read.
	 * @return The contents of the file
	 */
	private static String readFile(String fileName) {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(
					new FileInputStream(fileName)));
			StringBuffer sb = new StringBuffer();
			String line = null;
			while (true) {
				line = br.readLine();
				if (line == null) {
					break;
				}
				sb.append(line).append("\n");
			}
			return sb.toString();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
			return null;
		}
	}

	/**
	 * The OpenCL context
	 */
	private cl_context context;

	/**
	 * The OpenCL command queue
	 */
	private cl_command_queue commandQueue;

	/**
	 * The OpenCL kernel which will perform the convolution
	 */
	private cl_kernel clKernel;

	/**
	 * The kernel which is used for the convolution
	 */
	private Kernel kernel;

	/**
	 * The memory object that stores the kernel data
	 */
	private cl_mem kernelMem;

	/**
	 * The memory object for the input image
	 */
	private cl_mem inputImageMem;

	/**
	 * The memory object for the output image
	 */
	private cl_mem outputImageMem;

	/**
	 * Creates a new JOCLConvolveOp which may be used to apply the given kernel
	 * to a BufferedImage. This method will create an OpenCL context for the
	 * first platform that is found, and a command queue for the first device
	 * that is found. To create a JOCLConvolveOp for an existing context and
	 * command queue, use the constructor of this class.
	 * 
	 * @param kernel
	 *            The kernel to apply
	 * @return The JOCLConvolveOp for the given kernel.
	 */
	public static JOCLRenderizador create(Kernel kernel) {
		// The platform, device type and device number
		// that will be used
		final int platformIndex = 0;
		final long deviceType = CL.CL_DEVICE_TYPE_GPU;
		final int deviceIndex = 0;

		// Enable exceptions and subsequently omit error checks in this sample
		CL.setExceptionsEnabled(true);

		// Obtain the number of platforms
		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		// Obtain a platform ID
		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		cl_platform_id platform = platforms[platformIndex];

		// Initialize the context properties
		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		// Obtain the number of devices for the platform
		int numDevicesArray[] = new int[1];
		CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
		int numDevices = numDevicesArray[0];

		// Obtain a device ID
		cl_device_id devices[] = new cl_device_id[numDevices];
		CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
		cl_device_id device = devices[deviceIndex];

		// Create a context for the selected device
		cl_context context = CL.clCreateContext(contextProperties, 1,
				new cl_device_id[] { device }, null, null, null);

		// Create a command-queue for the selected device
		cl_command_queue commandQueue = CL.clCreateCommandQueue(context, device,
				0, null);

		return new JOCLRenderizador(context, commandQueue, kernel);
	}

	/**
	 * Creates a JOCLConvolveOp for the given context and command queue, which
	 * may be used to apply the given kernel to a BufferedImage.
	 * 
	 * @param context
	 *            The context
	 * @param commandQueue
	 *            The command queue
	 * @param kernel
	 *            The kernel to apply
	 */
	public JOCLRenderizador(cl_context context, cl_command_queue commandQueue,
			Kernel kernel) {
		this.context = context;
		this.commandQueue = commandQueue;
		this.kernel = kernel;

		// Create the OpenCL kernel from the program
		String source = readFile(KERNEL_SOURCE_FILE_NAME);
		cl_program program = CL.clCreateProgramWithSource(context, 1,
				new String[] { source }, null, null);
		String compileOptions = "-cl-mad-enable";
		CL.clBuildProgram(program, 0, null, compileOptions, null, null);
		clKernel = CL.clCreateKernel(program, "convolution", null);
		CL.clReleaseProgram(program);

		// Create the ... other kernel... for the convolution
		float kernelData[] = kernel.getKernelData(null);
		kernelMem = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY, kernelData.length
				* Sizeof.cl_uint, null, null);
		CL.clEnqueueWriteBuffer(commandQueue, kernelMem, true, 0,
				kernelData.length * Sizeof.cl_uint, Pointer.to(kernelData), 0,
				null, null);

	}

	/**
	 * Release all resources that have been created for this instance.
	 */
	public void shutdown() {
		CL.clReleaseMemObject(kernelMem);
		CL.clReleaseKernel(clKernel);
		CL.clReleaseCommandQueue(commandQueue);
		CL.clReleaseContext(context);
		
	}

	@Override
	public BufferedImage createCompatibleDestImage(BufferedImage src,
			ColorModel destCM) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage result = new BufferedImage(w, h,
				BufferedImage.TYPE_INT_RGB);
		return result;
	}

	@Override
	public BufferedImage filter(BufferedImage src, BufferedImage dst) {
		// Validity checks for the given images
		if (src.getType() != BufferedImage.TYPE_INT_RGB) {
			throw new IllegalArgumentException(
					"Source image is not TYPE_INT_RGB");
		}
		if (dst == null) {
			dst = createCompatibleDestImage(src, null);
		} else if (dst.getType() != BufferedImage.TYPE_INT_RGB) {
			throw new IllegalArgumentException(
					"Destination image is not TYPE_INT_RGB");
		}
		if (src.getWidth() != dst.getWidth()
				|| src.getHeight() != dst.getHeight()) {
			throw new IllegalArgumentException(
					"Images do not have the same size");
		}
		int imageSizeX = src.getWidth();
		int imageSizeY = src.getHeight();

		// Create the memory object for the input- and output image
		DataBufferInt dataBufferSrc = (DataBufferInt) src.getRaster()
				.getDataBuffer();
		int dataSrc[] = dataBufferSrc.getData();
		inputImageMem = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY
				| CL.CL_MEM_USE_HOST_PTR, dataSrc.length * Sizeof.cl_uint,
				Pointer.to(dataSrc), null);

		outputImageMem = CL.clCreateBuffer(context, CL.CL_MEM_WRITE_ONLY, imageSizeX
				* imageSizeY * Sizeof.cl_uint, null, null);

		// Set work sizes and arguments, and execute the kernel
		int kernelSizeX = kernel.getWidth();
		int kernelSizeY = kernel.getHeight();
		int kernelOriginX = kernel.getXOrigin();
		int kernelOriginY = kernel.getYOrigin();

		long localWorkSize[] = new long[2];
		localWorkSize[0] = kernelSizeX;
		localWorkSize[1] = kernelSizeY;

		long globalWorkSize[] = new long[2];
		globalWorkSize[0] = round(localWorkSize[0], imageSizeX);
		globalWorkSize[1] = round(localWorkSize[1], imageSizeY);

		int imageSize[] = new int[] { imageSizeX, imageSizeY };
		int kernelSize[] = new int[] { kernelSizeX, kernelSizeY };
		int kernelOrigin[] = new int[] { kernelOriginX, kernelOriginY };

		CL.clSetKernelArg(clKernel, 0, Sizeof.cl_mem, Pointer.to(inputImageMem));
		CL.clSetKernelArg(clKernel, 1, Sizeof.cl_mem, Pointer.to(kernelMem));
		CL.clSetKernelArg(clKernel, 2, Sizeof.cl_mem, Pointer.to(outputImageMem));
		CL.clSetKernelArg(clKernel, 3, Sizeof.cl_int2, Pointer.to(imageSize));
		CL.clSetKernelArg(clKernel, 4, Sizeof.cl_int2, Pointer.to(kernelSize));
		CL.clSetKernelArg(clKernel, 5, Sizeof.cl_int2, Pointer.to(kernelOrigin));

		CL.clEnqueueNDRangeKernel(commandQueue, clKernel, 2, null, globalWorkSize,
				localWorkSize, 0, null, null);

		// Read the pixel data into the BufferedImage
		DataBufferInt dataBufferDst = (DataBufferInt) dst.getRaster()
				.getDataBuffer();
		int dataDst[] = dataBufferDst.getData();
		CL.clEnqueueReadBuffer(commandQueue, outputImageMem, CL.CL_TRUE, 0,
				dataDst.length * Sizeof.cl_uint, Pointer.to(dataDst), 0, null,
				null);

		// Clean up
		CL.clReleaseMemObject(inputImageMem);
		CL.clReleaseMemObject(outputImageMem);

		return dst;
	}

	@Override
	public Rectangle2D getBounds2D(BufferedImage src) {
		return src.getRaster().getBounds();
	}

	@Override
	public final Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
		if (dstPt == null) {
			dstPt = new Point2D.Float();
		}
		dstPt.setLocation(srcPt.getX(), srcPt.getY());
		return dstPt;
	}

	@Override
	public RenderingHints getRenderingHints() {
		return null;
	}
	
	
	

}
